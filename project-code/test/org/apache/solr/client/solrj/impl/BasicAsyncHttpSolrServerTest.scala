package org.apache.solr.client.solrj.impl

import play.api.libs.concurrent.Execution.Implicits._
import play.api.test.{WithApplication, FakeApplication}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServlet}
import java.util
import java.util.concurrent.TimeoutException
import java.io.{InputStream, IOException}

import org.junit.{Ignore, AfterClass, Test, BeforeClass}
import org.junit.Assert._

import org.apache.solr.util.ExternalPaths
import org.apache.solr.client.solrj.embedded.JettySolrRunner
import org.apache.solr.client.solrj.{SolrServerException, SolrQuery}
import org.apache.solr.client.solrj.SolrRequest.METHOD
import org.apache.solr.SolrJettyTestBase
import org.apache.solr.common.SolrException.ErrorCode
import org.apache.solr.common.{SolrInputDocument, SolrException}
import org.apache.solr.common.params.CommonParams
import org.apache.solr.client.solrj.request.{AsyncQueryRequest, AsyncUpdateRequest}

import com.carrotsearch.randomizedtesting.ThreadFilter
import com.carrotsearch.randomizedtesting.annotations.{ThreadLeakScope, ThreadLeakFilters}

/**
 * @todo implement response compression and compression test
 */
object BasicAsyncHttpSolrServerTest {
  class RedirectServlet extends HttpServlet {
    protected override def doGet(req: HttpServletRequest,  resp: HttpServletResponse) = {
      resp.sendRedirect("/solr/collection1/select?" + req.getQueryString)
    }
  }

  class SlowServlet extends HttpServlet {
    protected override def doGet(req: HttpServletRequest,  resp: HttpServletResponse) = {
      try {
        Thread.sleep(5000)
      } catch {
        case e: InterruptedException =>
      }
    }
  }

  object DebugServlet {
    var errorCode:Int = 0
    var lastMethod:String = null
    var headers:util.HashMap[String, String] = null
    var parameters:util.Map[String, Array[String]] = null

    def clear() {
      lastMethod = null
      headers = null
      parameters = null
      errorCode = 0
    }
  }

  class DebugServlet extends HttpServlet {

    protected override def doGet(req: HttpServletRequest,  resp: HttpServletResponse) = {
      DebugServlet.lastMethod = "get"
      recordRequest(req, resp)
    }

    private def setHeaders(req: HttpServletRequest) = {
      val headerNames = req.getHeaderNames
      DebugServlet.headers = new util.HashMap[String, String]
      while (headerNames.hasMoreElements) {
        val name = headerNames.nextElement()
        DebugServlet.headers.put(name, req.getHeader(name))
      }
    }

    private def setParameters(req: HttpServletRequest) {
      DebugServlet.parameters = req.getParameterMap
    }

    protected override def doPost(req: HttpServletRequest,  resp: HttpServletResponse) = {
      DebugServlet.lastMethod = "post"
      recordRequest(req, resp)
    }

    private def recordRequest(req: HttpServletRequest, resp: HttpServletResponse) {
      setHeaders(req)
      setParameters(req)
      if (0 != DebugServlet.errorCode) {
        try {
          resp.sendError(DebugServlet.errorCode)
        } catch {
          case e: IOException => throw new RuntimeException("sendError IO fail in DebugServlet", e)
        }
      }
    }
  }

  @BeforeClass
  def beforeTest() : Unit = {
    val jetty:JettySolrRunner = SolrJettyTestBase.createJetty(ExternalPaths.EXAMPLE_HOME, null, null)
    jetty.getDispatchFilter.getServletHandler.addServletWithMapping(classOf[RedirectServlet], "/redirect/*")
    jetty.getDispatchFilter.getServletHandler.addServletWithMapping(classOf[SlowServlet], "/slow/*")
    jetty.getDispatchFilter.getServletHandler.addServletWithMapping(classOf[DebugServlet], "/debug/*")
  }


  @AfterClass
  def afterTest() = {
    DebugServlet.clear()
  }

}

class KnownPlayThread extends ThreadFilter {
  def reject(t: Thread) : Boolean = {
    t.getName.startsWith("play-scheduler") || t.getName.startsWith("play-akka")
  }
}

@ThreadLeakFilters(filters = Array(classOf[KnownPlayThread]))
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
class BasicAsyncHttpSolrServerTest extends SolrJettyTestBase {

  private val jetty:JettySolrRunner = SolrJettyTestBase.jetty
  private val DebugServlet = BasicAsyncHttpSolrServerTest.DebugServlet

  @Test
  def testTimeout() : Unit = {
    new WithApplication(FakeApplication()) {
      val server = AsyncHttpSolrServer(jetty.getBaseUrl.toString + "/slow/foo")
      val q = new SolrQuery("*:*")
      server.getTimeout = 2000
      server.postTimeout = 2000

      val response = server.query(q, METHOD.GET).map( response => {
        fail("No exception thrown.")
      }).recover {
        case e: SolrServerException =>  {
          val rootCause = e.getRootCause
          if (rootCause.isInstanceOf[TimeoutException]) {
            assertTrue("Exception message doesn't contain 'Request timed out to'", rootCause.getMessage.contains("Request timed out to"))
          } else {
            fail("Unexpected exception: " + rootCause.getMessage)
          }
        }
        case other => {
          fail("Unexpected exception: " + other.getMessage)
        }
      }

      try {
        Await.result(response, 8 seconds)
      } finally {
        server.shutdown()
      }
    }
  }

  /**
   * test that SolrExceptions thrown by HttpSolrServer can
   * correctly encapsulate http status codes even when not on the list of
   * ErrorCodes solr may return.
   *
   * @todo I realized this test is disabled in SolrJ after porting the code. The ErrorCode will return Unknown(0) for all
   *       Solr unknown errors which means SolrJ doesn't the way this test was intended for.
  */
  @Test
  @Ignore
  def testSolrExceptionCodeNotFromSolr() : Unit = {
    new WithApplication(FakeApplication()) {
      val status = 527
      assertEquals(status + " didn't generate an UNKNOWN error code, someone modified the list of valid ErrorCode's w/o changing this test to work a different way",
        ErrorCode.UNKNOWN, ErrorCode.getErrorCode(status))

      val server = AsyncHttpSolrServer(jetty.getBaseUrl.toString + "/debug/foo")
      DebugServlet.errorCode = status
      val q = new SolrQuery("foo")

      val response = server.query(q, METHOD.GET).map( response => {
        fail("Didn't get excepted exception from oversided request")
      }).recover {

        case e: SolrException => {
          assertEquals("Unexpected exception status code", status, e.code())
        }
      }

      try {
        Await.result(response, 4 seconds)
      } finally {
        DebugServlet.clear()
        server.shutdown()
      }
    }
  }

  @Test
  def testQuery() : Unit = {
    new WithApplication(FakeApplication()) {
      DebugServlet.clear()
      val server = AsyncHttpSolrServer(jetty.getBaseUrl.toString + "/debug/foo")
      val q = new SolrQuery("foo")
      q.setParam("a", "\u1234")

      var response = server.query(q, METHOD.GET).map( response => {
        // do nothing
        response
      } ).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)

        //default method
        assertRequest(server, "get", "javabin", null, "a", "\u1234")
        assertEquals("keep-alive", DebugServlet.headers.get("Connection"))
        //content-type
        assertEquals(null, DebugServlet.headers.get("Content-Type"))
      } finally {
        DebugServlet.clear()
      }

      //POST
      response = server.query(q, METHOD.POST).map( response => {
        // do nothing
        response
      } ).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        assertRequest(server, "post", "javabin", "application/x-www-form-urlencoded; charset=UTF-8", "a", "\u1234")
        assertEquals("keep-alive", DebugServlet.headers.get("Connection"))
        assertEquals("UTF-8", DebugServlet.headers.get("Content-Charset"))
      } finally {
        DebugServlet.clear()
      }

      //XML/GET
      server.parser = new XMLResponseParser()
      response = server.query(q, METHOD.GET).map( response => {
        // do nothing
        response
      } ).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        assertRequest(server, "get", "xml", null, "a", "\u1234")
        assertEquals("keep-alive", DebugServlet.headers.get("Connection"))
      } finally {
        DebugServlet.clear()
      }

      //XML/POST
      server.parser = new XMLResponseParser()
      response = server.query(q, METHOD.POST).map( response => {
        // do nothing
        response
      } ).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        assertRequest(server, "post", "xml", "application/x-www-form-urlencoded; charset=UTF-8", "a", "\u1234")
        assertEquals("keep-alive", DebugServlet.headers.get("Connection"))
        assertEquals("UTF-8", DebugServlet.headers.get("Content-Charset"))
      } finally {
        DebugServlet.clear()
        server.shutdown()
      }
    }
  }

  @Test
  def testDelete() : Unit = {
    new WithApplication(FakeApplication()) {
      DebugServlet.clear()
      val server = AsyncHttpSolrServer(jetty.getBaseUrl.toString + "/debug/foo")

      var response = server.deleteById("id").map( response => {
        // do nothing
        response
      }).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        //default method
        assertRequest(server, "post", "javabin", "application/xml; charset=UTF-8")
        //keepalive
        assertEquals("keep-alive", DebugServlet.headers.get("Connection"))
      } finally {
        DebugServlet.clear()
      }

      //XML
      server.parser = new XMLResponseParser()

      response = server.deleteByQuery("*:*").map( response => {
        // do nothing
        response
      }).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        assertRequest(server, "post", "xml", "application/xml; charset=UTF-8")
        assertEquals("keep-alive", DebugServlet.headers.get("Connection"))
      } finally {
        DebugServlet.clear()
        server.shutdown()
      }
    }
  }

  @Test
  def testUpdate() : Unit = {
    new WithApplication(FakeApplication()) {
      DebugServlet.clear()
      val server = AsyncHttpSolrServer(jetty.getBaseUrl.toString + "/debug/foo")
      val req = new AsyncUpdateRequest

      req.add(new SolrInputDocument())
      req.setParam("a", "\u1234")

      var response = server.request(req).map( response => {
         // do nothing
         response
      }).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        // default method
        assertRequest(server, "post", "javabin", "application/xml; charset=UTF-8", "a", "\u1234")
      } finally {
        DebugServlet.clear()
      }

      //XML response
      server.parser = new XMLResponseParser()
      response = server.request(req).map( response => {
         // do nothing
         response
      }).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        assertRequest(server, "post", "xml", "application/xml; charset=UTF-8", "a", "\u1234")
      } finally {
        DebugServlet.clear()
      }

      //javabin request
      server.parser = new BinaryResponseParser
      server.requestWriter = new BinaryRequestWriter
      response = server.request(req).map( response => {
         // do nothing
         response
      }).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
        assertRequest(server, "post", "javabin", "application/javabin", "a", "\u1234")
      } finally {
        DebugServlet.clear()
        server.shutdown()
      }
    }
  }

  @Test
 	def testRedirectWithNoFollowing() : Unit = {
    new WithApplication(FakeApplication()) {
 	    val server = AsyncHttpSolrServer(jetty.getBaseUrl +"/redirect/foo")
 	    val q = new SolrQuery("*:*")

 	    // default = false
      var response = server.query(q).map( response => {
         // do nothing
         response
      }).recover {
        case e: SolrServerException => assertTrue(e.getMessage.contains("redirect"))
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
      } finally {
        DebugServlet.clear()
        server.shutdown()
      }
    }
 	}

  @Test
 	def testRedirectWithFollowing() : Unit = {
    new WithApplication(FakeApplication()) {
 	    val server = AsyncHttpSolrServer(jetty.getBaseUrl +"/redirect/foo")
 	    val q = new SolrQuery("*:*")

 	    server.followRedirects = true
      val response = server.query(q).map( response => {
         // do nothing
         response
      }).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
      } finally {
        DebugServlet.clear()
        server.shutdown()
      }
    }
 	}

  @Test
 	def testGetRawStream() : Unit = {
    new WithApplication(FakeApplication()) {
      val server = new AsyncHttpSolrServer(jetty.getBaseUrl + "/collection1", null)
      val req = new AsyncQueryRequest(new SolrQuery("foo"))
      var response = server.request(req).map( response => {
        val stream = response.get("stream").asInstanceOf[InputStream]
        assertNotNull(stream)
        stream.close()
        response
      }).recover {
        case other : Throwable => // do nothing
      }

      try {
        Await.result(response, 4 seconds)
      } finally {
        DebugServlet.clear()
        server.shutdown()
      }
    }
  }

  private def assertRequest(server: AsyncHttpSolrServer, expectedMethod: String, expectedWriterType: String, expectedContentType: String) : Unit = {
    assertEquals(expectedMethod, DebugServlet.lastMethod)
    assertEquals(s"Solr[${classOf[AsyncHttpSolrServer].getName}] 1.0", DebugServlet.headers.get("User-Agent"))
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length)
    assertEquals(expectedWriterType, DebugServlet.parameters.get(CommonParams.WT)(0))
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length)
    assertEquals(server.parser.getVersion, DebugServlet.parameters.get(CommonParams.VERSION)(0))
    assertEquals(expectedContentType, DebugServlet.headers.get("Content-Type"))
  }

  private def assertRequest(server: AsyncHttpSolrServer, expectedMethod: String, expectedWriterType: String,
                            expectedContentType: String, anyParamName: String, expectedAnyParamValue: String)  : Unit = {
    assertRequest(server, expectedMethod, expectedWriterType, expectedContentType)
    assertEquals(1, DebugServlet.parameters.get(anyParamName).length)
    assertEquals(expectedAnyParamValue, DebugServlet.parameters.get(anyParamName)(0))
  }
}