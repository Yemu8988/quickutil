package com.quickutil.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.quickutil.platform.def.BulkResponse;
import com.quickutil.platform.def.SearchRequest;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NoHttpResponseException;
import org.apache.http.TruncatedChunkException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

/**
 * @author shijie.ruan
 */
public class ElasticUtil {
	private static final String hostIndexFormat = "%s/%s/";
	private static final String hostIndexTypeFormat = "%s/%s/%s/";

	private final String host;
	private final Version version;

	public final HttpClient client;

	private static RequestConfig requestConfig = RequestConfig.custom()
			.setConnectionRequestTimeout(2*60000)
			.setConnectTimeout(2*60000)
			.setSocketTimeout(2*60000).build();

	private static PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();

	private static HttpRequestRetryHandler httpRequestRetryHandler = new HttpRequestRetryHandler() {
		public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
			if (executionCount >= 5) {// 如果已经重试了5次，就放弃
				return false;
			}
			if (exception instanceof NoHttpResponseException) {// 如果服务器丢掉了连接，那么就重试
				return true;
			}
			if (exception instanceof SSLHandshakeException) {// 不要重试SSL握手异常
				return false;
			}
			if (exception instanceof SocketTimeoutException) {
				return true;
			}
			if (exception instanceof InterruptedIOException) {// 超时
				return true;
			}
			if (exception instanceof UnknownHostException) {// 目标服务器不可达
				return false;
			}
			if (exception instanceof SSLException) {// SSL握手异常
				return false;
			}
			if (exception instanceof TruncatedChunkException) {
				return true;
			}
			HttpClientContext clientContext = HttpClientContext.adapt(context);
			HttpRequest request = clientContext.getRequest();
			if (!(request instanceof HttpEntityEnclosingRequest)) { // 如果请求是幂等的，就再次尝试
				return true;
			}
			return false;
		}
	};

	static {
		cm.setMaxTotal(50);
		cm.setDefaultMaxPerRoute(50);
	}

	public ElasticUtil(String host, Version version) {
		this.host = host;
		this.version = version;
		cm.setMaxPerRoute(new HttpRoute(new HttpHost(host)), 50);
		this.client = HttpClients.custom().setConnectionManager(cm).setRetryHandler(httpRequestRetryHandler).build();
	}

	public Version getVersion() {
		return this.version;
	}

	private HttpUriRequest getMethod(String url) {
		HttpGet httpGet = new HttpGet(url);
		httpGet.setConfig(requestConfig);
		return httpGet;
	}

	private HttpUriRequest postMethod(String url, String entity) {
		HttpPost httpPost = new HttpPost(url);
		httpPost.setConfig(requestConfig);
		if (null != entity && !entity.isEmpty()) {
			httpPost.setEntity(new ByteArrayEntity(entity.getBytes()));
		}
		return httpPost;
	}

	/**
	 * 使用id查询数据 有任何错误返回都返回空
	 *
	 * @param index-ES的index
	 * @param type-ES的type
	 * @param id-ES的id
	 * @return
	 */
	public String selectById(String index, String type, String id) {
		String url = String.format("%s/%s/%s/%s/_source", host, index, type, id);
		try {
			HttpResponse response = client.execute(getMethod(url));
			if (response == null)
				return null;
			else if (200 != response.getStatusLine().getStatusCode()) {
				return null;
			} else {
				return getEntity(response);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * 写入一个文档,返回成功或失败
	 *
	 * @param index-ES的index
	 * @param type-ES的type
	 * @param id-ES的id
	 * @param source-写入的内容
	 * @return
	 */
	public boolean insert(String index, String type, String id, JsonObject source) {
		String url = null;
		try {
			if (null == index || null == type || id == null) {
				return false;
			}
			url = String.format("%s/%s/%s/%s", host, index, type, id).replace(" ", "");
			String sourceString = (null == source ? "{}" : JsonUtil.toJson(source));
			HttpResponse response = client.execute(postMethod(url, sourceString));
			if (200 == response.getStatusLine().getStatusCode() || 201 == response.getStatusLine().getStatusCode()) {
				return true;
			} else {
				System.out.println("fail on url: " + url + "\nwith source: " + sourceString + "\nresponse: " + getEntity(response));
				return false;
			}
		} catch (Exception e) {
			System.out.println("fail on url: " + url);
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * 部分更新一个文档,返回成功或失败
	 *
	 * @param index-ES的index
	 * @param type-ES的type
	 * @param id-ES的id
	 * @param source-更新的内容
	 * @param isUpsert-true,表示如果文档不存在则插入,false,时如果不存在则不插入
	 * @return
	 */
	public boolean update(String index, String type, String id, JsonObject source, boolean isUpsert) {
		String sourceString = null, url = null;
		try {
			if (null == index || null == type || id == null) {
				System.out.println("[index], [type], [id] must be not null");
				return false;
			}
			url = String.format("%s/%s/%s/%s/_update", host, index, type, id).replace(" ", "");
			Map<String, Object> map = new HashMap<String, Object>();
			if (null == source) {
				source = new JsonObject();
			}
			map.put("doc", source);
			map.put("doc_as_upsert", isUpsert);
			sourceString = JsonUtil.toJson(map);
			HttpResponse response = client.execute(postMethod(url, sourceString));
			if (200 == response.getStatusLine().getStatusCode() || 201 == response.getStatusLine().getStatusCode()) {
				return true;
			} else if (404 == response.getStatusLine().getStatusCode()) {
				System.out.format("[%s][%s][%s]: document missing\n", index, type, id);
				return false;
			} else {
				System.out.println("fail on url: " + url + "\nwith source: " + sourceString + "\nresponse: " + getEntity(response));
				return false;
			}
		} catch (Exception e) {
			System.out.println("fail on url: " + url + "\n" + sourceString + "\n");
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * 发起批量请求, 支持 index, create, update, delete(被屏蔽), 其中 index, update, create 下一行都需要是 文档的内容, delete 下一行不能是文档的内容
	 * 
	 * @param url
	 * @param entity
	 * @return
	 */
	private BulkResponse bulk(String url, String entity) {
		try {
			long start = System.currentTimeMillis();
			HttpResponse response = client.execute(postMethod(url, entity));
			System.out.println("time for execute bulk:" + (System.currentTimeMillis() - start));
			if (200 != response.getStatusLine().getStatusCode()) {
				JsonObject bulkRequestError = JsonUtil.toJsonMap(getEntity(response)).getAsJsonObject("error");
				return new BulkResponse(BulkResponse.RequestFail, bulkRequestError);
			} else {
				start = System.currentTimeMillis();
				JsonObject responseObject = JsonUtil.toJsonMap(getEntity(response));
				boolean hasErrors = responseObject.get("errors").getAsBoolean();
				if (!hasErrors) {
					return new BulkResponse(BulkResponse.Success);
				} else {
					JsonArray responseArray = responseObject.getAsJsonArray("items");
					return new BulkResponse(BulkResponse.PortionFail, responseArray);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new BulkResponse(BulkResponse.RequestFail, new JsonObject());
		}
	}

	/**
	 * 缓存队列式批量写入，每秒写入一次, 时间不到1s, 返回写入成功,其实在程序缓存中, 这时候如果程序崩溃或者重启会丢失这一秒的数据
	 *
	 * @param index-ES的index
	 * @param type-ES的type
	 * @param id-ES的id
	 * @param source-写入的内容
	 * @return
	 */
	private long lasttime = 0;
	private StringBuffer sb = new StringBuffer();
	private int count = 0;

	private static final String actionAndMeta = "{\"index\":{\"_index\":\"%s\",\"_type\":\"%s\",\"_id\":\"%s\"}}\n";

	public boolean bulkInsertBuffer(String index, String type, String id, String source) {
		String entity = null;
		try {
			sb.append(String.format(actionAndMeta, index, type, id) + source + "\n");
			if (System.currentTimeMillis() - lasttime > 1000) {
				entity = sb.toString();
				lasttime = System.currentTimeMillis();
				sb = new StringBuffer();
				System.out.println("content count:" + count);
				BulkResponse response = bulk(String.format("%s/_bulk", host), entity);
				if (!response.isSuccess()) {
					System.out.println("fail--" + response.errorMessage());
					System.out.println(entity);
					return false;
				} else {
					return true;
				}
			} else {
				return true;
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("fail--exception");
			System.out.println(entity);
		}
		return false;
	}

	/**
	 * 批量写入,写入同一个 index 和 type
	 *
	 * @param index-ES的index
	 * @param type-ES的type
	 * @param source-写入的内容，key为id，value为source
	 * @return
	 */
	public BulkResponse bulkInsert(String index, String type, Map<String, String> source) {
		String idFormat = "{\"index\": {\"_id\": \"%s\"}}\n";
		if (null == index || null == type) {
			JsonObject insertError = new JsonObject();
			insertError.addProperty("msg", "bulk insert must specify index and type");
			return new BulkResponse(BulkResponse.RequestFail, insertError);
		}
		StringBuilder entity = new StringBuilder();
		for (String id : source.keySet()) {
			entity.append(String.format(idFormat, id)).append(source.get(id)).append("\n");
		}
		String urlFormat = "%s/%s/%s/_bulk";
		return bulk(String.format(urlFormat, host, index, type), entity.toString());
	}

	/**
	 * 批量插入,使用 stringBuffer
	 *
	 * @param stringBuffer-由调用者编写批量插入的内容,可以不是同一个 index 和 type
	 * @return
	 */
	public BulkResponse bulkInsertByStringBuffer(StringBuffer stringBuffer) {
		String urlFormat = "%s/_bulk";
		return bulk(String.format(urlFormat, host), stringBuffer.toString());
	}

	/**
	 * 批量更新,同一个 index 和 type
	 *
	 * @param index-ES的index
	 * @param type-ES的type
	 * @param source-更新的内容,
	 *            key为id，value为source
	 * @param upsert-文档不存在时插入,其实控制粒度是对于每一个文档的,但是这里为了方便输入,粒度为同一次批量的文档
	 * @return
	 */
	public BulkResponse bulkUpdate(String index, String type, Map<String, JsonObject> source, boolean upsert) {
		String idFormat = "{\"update\": {\"_id\": \"%s\"}}\n";
		if (null == index || null == type) {
			JsonObject insertError = new JsonObject();
			insertError.addProperty("msg", "bulk update must specify index and type");
			return new BulkResponse(BulkResponse.RequestFail, insertError);
		}
		StringBuilder entity = new StringBuilder();
		for (String id : source.keySet()) {
			JsonObject item = new JsonObject();
			item.add("doc", source.get(id));
			if (upsert) {
				item.addProperty("doc_as_upsert", true);
			}
			entity.append(String.format(idFormat, id)).append(item).append("\n");
		}
		String urlFormat = "%s/%s/%s/_bulk";
		return bulk(String.format(urlFormat, host, index, type), entity.toString());
	}

	/**
	 * 批量更新,使用同一个脚本同一个 index 和 type.
	 * 
	 * @param index-ES的index
	 * @param type-ES的type
	 * @param source-更新的内容,
	 *            key为id，value为source
	 * @param scriptFile-放在
	 *            elasticsearch home 目录下的 config/script 目录下的groovy脚本, 为了安全,不支持在请求中带上脚本 之所以是 groovy 脚本,因为 groovy 是 2.x 和 5.x 都支持的
	 * @param lang-脚本的语言类型, 支持 groovy, painless
	 * @param upsert-文档不存在时插入,其实控制粒度是对于每一个文档的,但是这里为了方便输入,粒度为同一次批量的文档
	 * @return
	 */
	public BulkResponse bulkUpdateByScript(String index, String type, Map<String, JsonObject> source, String scriptFile, String lang, boolean upsert) {
		String idFormat = "{\"update\": {\"_id\": \"%s\"}}\n";
		if (null == index || null == type) {
			JsonObject insertError = new JsonObject();
			insertError.addProperty("msg", "bulk update must specify index and type");
			return new BulkResponse(BulkResponse.RequestFail, insertError);
		}
		StringBuilder entity = new StringBuilder();
		for (String id : source.keySet()) {
			JsonObject item = new JsonObject();
			JsonObject scriptObject = new JsonObject();
			scriptObject.addProperty("lang", lang);
			scriptObject.addProperty("file", scriptFile);
			scriptObject.add("params", source.get(id));
			item.add("script", scriptObject);
			if (upsert) {
				item.add("upsert", source.get(id));
			}
			entity.append(String.format(idFormat, id)).append(item).append("\n");
		}
		String urlFormat = "%s/%s/%s/_bulk";
		return bulk(String.format(urlFormat, host, index, type), entity.toString());
	}

	/**
	* 使用 groovy 脚本
	*/
	public BulkResponse bulkUpdateByScript(String index, String type, Map<String, JsonObject> source, String scriptFile, boolean upsert) {
		return bulkUpdateByScript(index, type, source, scriptFile, "groovy", upsert);
	}

	/**
	 * 批量更新,使用 stringBuffer
	 * 
	 * @param stringBuffer-由调用者编写批量插入的内容,可以不是同一个
	 *            index 和 type
	 * @return
	 */
	public BulkResponse bulkUpdateByStringBuffer(StringBuffer stringBuffer) {
		String urlFormat = "%s/_bulk";
		return bulk(String.format(urlFormat, host), stringBuffer.toString());
	}

	/**
	 * 批量更新,使用 stringBuffer
	 *
	 * @param index-写入的 index
	 * @param type-写入的 type
	 * @param stringBuffer-由调用者编写批量插入的内容,可以不是同一个
	 *            index 和 type
	 * @return
	 */
	public BulkResponse bulkUpdateByStringBuffer(String index, String type, StringBuffer stringBuffer) {
		String urlFormat = "%s/%s/%s/_bulk";
		return bulk(String.format(urlFormat, host, index, type), stringBuffer.toString());
	}

	/**
	 * 批量删除, 注意输入参数中的三个 list 长度需要一致
	 *
	 * @param indices-删除的 index
	 * @param types-删除的 type
	 * @param ids-删除的 id
	 * @return
	 */
	public BulkResponse bulkDelete(List<String> indices, List<String> types, List<String> ids) {
		String deleteFormat = "{\"delete\":{\"_index\":\"%s\",\"_type\":\"%s\",\"_id\":\"%s\"}}\n";
		StringBuilder bulk = new StringBuilder();
		for (int i = 0; i < indices.size(); i++) {
			bulk.append(String.format(deleteFormat, indices.get(i), types.get(i), ids.get(i)));
		}
		String urlFormat = "%s/_bulk";
		return bulk(String.format(urlFormat, host), bulk.toString());
	}

	/**
	 * 查询或者聚合请求
	 * 
	 * @param index
	 *            ES的index(可以包含*作为通配符)
	 * @param type
	 *            ES的type(可以为空)
	 * @param searchRequest
	 * @return
	 */
	public String search(String index, String type, SearchRequest searchRequest) {
		try {
			String url = null == type ? String.format(hostIndexFormat, host, index) + "_search" : String.format(hostIndexTypeFormat, host, index, type) + "_search";
			System.out.println(searchRequest.toJson());
			HttpResponse response = client.execute(postMethod(url, searchRequest.toJson()));
			if (200 != response.getStatusLine().getStatusCode()) {
				System.out.println("search fail on url: " + url + ", response:\n" + getEntity(response));
				return null;
			}
			return getEntity(response);
		} catch (Exception e) {
			System.out.println("format search request fail, pls check");
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 对同一个 index(可以包含*作为通配符) 和 type(可以为空) 进行批量搜索
	 *
	 * @param index-ES的index
	 * @param type-ES的type(可以为空)
	 * @param searches-请求的列表
	 * @return
	 */
	public String mSearch(String index, String type, List<SearchRequest> searches) {
		String url = null;
		try {
			url = null == type ? String.format(hostIndexFormat, host, index) + "_msearch" : String.format(hostIndexTypeFormat, host, index, type) + "_msearch";
			StringBuilder entity = new StringBuilder();
			for (SearchRequest searchRequest : searches) {
				entity.append("{}\n");
				entity.append(searchRequest.toJson());
			}
			HttpResponse response = client.execute(postMethod(url, entity.toString()));
			if (200 != response.getStatusLine().getStatusCode()) {
				System.out.println("search fail on url: " + url + "with source:\n" + entity.toString());
				return null;
			}
			return getEntity(response);
		} catch (Exception e) {
			System.out.println("search fail on url: " + url);
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * 正则获取所有相关的 index 名字
	 * 
	 * @return
	 */
	public String[] getIndexName(String indexNameReg) {
		try {
			HttpResponse response = client.execute(getMethod(host + "/_cat/indices/" + indexNameReg));
			if (200 != response.getStatusLine().getStatusCode()) {
				System.out.println("get index name error, with response: " + getEntity(response));
				return null;
			}
			String[] indicesStats = getEntity(response).split("\\n");
			String[] indicesNames = new String[indicesStats.length];
			// 返回的 index 信息形如"green open activation-2014 5 1 2944 4522 2.6mb 1.3mb ",需要获取名字
			for (int i = 0; i < indicesStats.length; i++) {
				String indexName = indicesStats[i].split("\\s+")[2];
				indicesNames[i] = indexName;
			}
			return indicesNames;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public String getFirstScrollSearch(String index) {
		return getFirstScrollSearch(index, null, null);
	}

	public String getFirstScrollSearch(String index, SearchRequest searchRequest) {
		return getFirstScrollSearch(index, null, searchRequest);
	}

	/**
	 * 获取首次 scroll 查询
	 * 
	 * @param index
	 *            可以是通配符,不能为空
	 * @param type
	 *            可以为空
	 * @param searchRequest
	 *            可以为空
	 * @return
	 */
	public String getFirstScrollSearch(String index, String type, SearchRequest searchRequest) {
		String url = null == type ? String.format(hostIndexFormat + "_search?scroll=5m", host, index) : String.format(hostIndexTypeFormat + "_search?scroll=5m", host, index, type);
		try {
			String query = (null == searchRequest ? "" : searchRequest.toJson());
			HttpResponse response = client.execute(postMethod(url, query));
			if (200 != response.getStatusLine().getStatusCode()) {
				System.out.println("fail on scroll search :" + url + "\n response: " + getEntity(response));
				return null;
			}
			return getEntity(response);
		} catch (Exception e) {
			System.out.println("fail on scroll search url: " + url);
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * 获取后续的 scroll 搜索的 url
	 * 
	 * @param scrollId
	 * @return
	 */
	public String getScrollSearch(String scrollId) {
		String url = String.format("%s/_search/scroll?scroll=5m&scroll_id=%s", host, scrollId);
		try {
			HttpResponse response = client.execute(getMethod(url));
			if (200 != response.getStatusLine().getStatusCode()) {
				System.out.println("fail on scroll search :" + url + "\n response: " + getEntity(response));
				return null;
			}
			return getEntity(response);
		} catch (Exception e) {
			System.out.println("fail on scroll search url: " + url);
			e.printStackTrace();
		}
		return null;
	}

	private String getEntity(HttpResponse response) throws IOException {
		return EntityUtils.toString(response.getEntity());
	}

	/**
	 * 查看 index 是否存在
	 * 
	 * @param index
	 * @return
	 */
	public boolean checkIndexExist(String index) {
		String getIndexExistUrl = String.format(hostIndexFormat, host, index);
		try {
			HttpResponse response = client.execute(getMethod(getIndexExistUrl));
			if (404 == response.getStatusLine().getStatusCode()) {
				return false;
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
		return true;
	}

	/**
	 * 创建索引, 请先运行 checkIndexExist 判断是否存在,不要直接运行
	 * 
	 * @param index
	 * @param mappings
	 * @return
	 */
	public boolean createIndex(String index, String mappings) {
		String createIndexUrl = String.format(hostIndexFormat, host, index);
		try {
			HttpResponse response = HttpUtil.httpPut(createIndexUrl, mappings.getBytes());
			if (200 != response.getStatusLine().getStatusCode()) {
				System.out.println("create index fail, response: " + getEntity(response));
				return false;
			}
			return true;
		} catch (Exception var3) {
			var3.printStackTrace();
			return false;
		}
	}

	/**
	 * 获取 index 的 mapping
	 * 
	 * @param index
	 * @return
	 */
	public String getMapping(String index) {
		String getMappingsUrl = String.format(hostIndexFormat + "_mapping", host, index);
		try {
			HttpResponse response = HttpUtil.httpGet(getMappingsUrl);
			return new String(FileUtil.stream2byte(response.getEntity().getContent()));
		} catch (Exception var3) {
			var3.printStackTrace();
			return null;
		}
	}

	/**
	 * 将 jsonObject 组成 jsonArray
	 * 
	 * @param jObjects
	 * @return
	 */
	public JsonArray jObjectMakeupJArray(JsonObject... jObjects) {
		JsonArray jArray = new JsonArray();
		for (JsonObject jObject : jObjects) {
			jArray.add(jObject);
		}
		return jArray;
	}

	/**
	 * 将 String 组成 jsonArray
	 * 
	 * @param strings
	 * @return
	 */
	public JsonArray stringMakeupJArray(String... strings) {
		JsonArray jArray = new JsonArray();
		for (String str : strings) {
			jArray.add(str);
		}
		return jArray;
	}

	/**
	 * 查询 es 的结果保存成 csv
	 * 
	 * @param index
	 * @param type
	 * @param searchRequest
	 * @param filePath
	 * @param jsonToCSV 将 hit 变成 csv 的一行
	 */
	public void dumpESDataToCsv(String index, String type, SearchRequest searchRequest, String filePath, Function<JsonObject, String> jsonToCSV) {
		assert (index != null && filePath != null && jsonToCSV != null);
		try {
			File f = new File(filePath);
			if (!f.exists()) {
				f.createNewFile();
			}
			String resp = getFirstScrollSearch(index, type, searchRequest);
			if (null == resp) {
				System.out.println("search get empty result, terminate.");
				return;
			}
			JsonObject result = JsonUtil.toJsonMap(resp);
			String scrollId;
			JsonArray array = result.getAsJsonObject("hits").getAsJsonArray("hits");
			long total = result.getAsJsonObject("hits").get("total").getAsLong();
			long count = 0;
			while (array.size() > 0) {
				StringBuilder bulk = new StringBuilder();
				for (JsonElement e : array) {
					JsonObject doc = e.getAsJsonObject();
					String csvLine = jsonToCSV.apply(doc);
					if (csvLine != null) {
						bulk.append(csvLine + "\n");
						count++;
					}
				}
				// append file
				FileUtil.string2File(filePath, bulk.toString(), true);
				scrollId = result.get("_scroll_id").getAsString();
				resp = getScrollSearch(scrollId);
				if (null == resp) {
					return;
				}
				array = JsonUtil.toJsonMap(resp).getAsJsonObject("hits").getAsJsonArray("hits");
				System.out.println("index: " + index + ShellUtil.printProgress((double) count / total));
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public enum Version {
		es2, es5
	}

	/**
	 * {a,b}
	 * 用中括号和逗号进行包装, 用于聚合返回的结果中带上多个源字段，或者搜索多个字段
	 * ["a", "b"]
	 * @param array
	 * @return
	 */
	public static String joinStringForMultiFields(String[] array) {
		for (int i = 0; i < array.length; i++) {
			array[i] = "\"" + array[i] + "\"";
		}
		return StringUtil.joinString(array, ",", "[", "]");
	}

	/**
	 * 把某个字段的多个可匹配值作为或条件子查询
	 * @param array
	 * @return
	 */
	public static String joinORSubQuery(String[] array) {
		for (int i = 0; i < array.length; i++) {
			if (array[i].contains(" "))
				array[i] = "\"" + array[i] + "\"";
		}
		return StringUtil.joinString(array, " OR ", "(", ")");
	}

	private static String[] propertiesKey =
			{"bucket", "region", "endpoint", "access_key", "secret_key"};

	/**
	 * 新建一个 s3 repository
	 * @param properties-需要包含bucket, regoin, endpoint, accessKey, secretKey 具体参数的意义请参考 es 文档
	 * @param repo repository 的名字
	 * @return
	 */
	public boolean createS3Repository(String repo, Properties properties) {
		JsonObject settings = new JsonObject();
		for (String key: propertiesKey) {
			if (properties.containsKey(key))
				settings.addProperty(key, properties.getProperty(key));
		}
		JsonObject repository = new JsonObject();
		repository.addProperty("type", "s3");
		repository.add("settings", settings);
		String url = String.format("%s/_snapshot/%s", host, repo);
		HttpPut httpPut = new HttpPut(url);
		httpPut.setConfig(requestConfig);
		httpPut.setEntity(new ByteArrayEntity(repository.toString().getBytes()));
		try {
			HttpResponse response = client.execute(httpPut);
			if (200 == response.getStatusLine().getStatusCode())
				return true;
			System.out.println(getEntity(response));
			return false;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}

	public boolean checkRepositoryExist(String repo) {
		String url = String.format("%s/_snapshot/%s", host, repo);
		try {
			HttpResponse response = client.execute(getMethod(url));
			if (200 == response.getStatusLine().getStatusCode())
				return true;
			return false;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * 在某个 repository 下生成 snapshot
	 * @param repositoryName
	 * @param snapshotName
	 * @param config
	 */
	public boolean createSnapshot(String repositoryName, String snapshotName, JsonObject config) {
		String url = String.format("%s/_snapshot/%s/%s", host, repositoryName, snapshotName);
		HttpPut httpPut = new HttpPut(url);
		httpPut.setConfig(requestConfig);
		httpPut.setEntity(new ByteArrayEntity(config.toString().getBytes()));
		try {
			client.execute(httpPut);
			return true;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * 从某个 repository 下恢复 snapshot
	 * @param repositoryName
	 * @param snapshotName
	 * @param config
	 */
	public boolean restoreSnapshot(String repositoryName, String snapshotName, JsonObject config) {
		String url = String.format("%s/_snapshot/%s/%s/_restore", host, repositoryName, snapshotName);
		HttpPut httpPut = new HttpPut(url);
		httpPut.setConfig(requestConfig);
		httpPut.setEntity(new ByteArrayEntity(config.toString().getBytes()));
		try {
			client.execute(httpPut);
			return true;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}
}
