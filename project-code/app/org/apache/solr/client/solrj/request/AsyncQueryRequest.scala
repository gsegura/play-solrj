package org.apache.solr.client.solrj.request

/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/


import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.{Future}

import org.apache.solr.common.params.SolrParams
import org.apache.solr.common.SolrException
import org.apache.solr.client.solrj.{AsyncSolrServer, SolrServerException}
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.SolrRequest.METHOD


class AsyncQueryRequest(params: SolrParams, method: METHOD) extends QueryRequest(params, method) with AsyncRequest {

  def this(params: SolrParams) = this(params, METHOD.GET)

  def process(server: AsyncSolrServer) : Future[QueryResponse] = {
    val startTime = System.currentTimeMillis()

    server.request(this).map { response =>
      // @todo passing null solr server to query response. QueryResponse use the server's
      // bean binder. Beans are not supported yet. WIll revisit latter
      val res = new QueryResponse(response, null)
      res.setElapsedTime(System.currentTimeMillis() - startTime)
      res
    }.recoverWith {
      case e: SolrServerException => Future.failed(e)
      case e: SolrException => Future.failed(e)
      case e: Exception => Future.failed(new SolrServerException(s"Error executing query [${this.getParams}}]", e))
    }

  }

}
