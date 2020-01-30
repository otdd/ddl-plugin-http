package io.otdd.ddl.http;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.http.ArrayUtil;
import org.apache.mina.http.HttpRequestImpl;
import org.apache.mina.http.api.DefaultHttpResponse;
import org.apache.mina.http.api.HttpMessage;
import org.apache.mina.http.api.HttpMethod;
import org.apache.mina.http.api.HttpRequest;
import org.apache.mina.http.api.HttpResponse;
import org.apache.mina.http.api.HttpStatus;
import org.apache.mina.http.api.HttpVersion;

public class HttpDecoder {

	public static final int TYPE_REQUEST = 0;
	public static final int TYPE_RESPONSE = 1;
	
	private static final Logger LOG = LogManager.getLogger();

	private State state = null;

	private int BODY_REMAINING_BYTES = 0;

	private ByteBuffer tmpBuffer = null;

	private IoBuffer data;
	private IoBuffer header;
	private IoBuffer body;
	
	private int type = 0;
	private HttpResponse httpResponse;
	private HttpRequest httpRequest;
	
	private HttpMessage httpMessage;

	enum ConsumeType{
		DISCARD,
		HEADER,
		BODY
	}

	enum State {
		NEW,
		HEAD,
		BODY_WITH_CONTENT_LENGTH,
		BODY_CHUNKED_NEW,
		BODY_CHUNKED_HEAD,
		BODY_CHUNKED_BODY
	}

	/** Regex to parse HttpRequest Request Line */
	private static final Pattern REQUEST_LINE_PATTERN = Pattern.compile(" ");

	/** Regex to parse HttpRequest Request Line */
	private static final Pattern RESPONSE_LINE_PATTERN = Pattern.compile(" ");

	/** Regex to parse out QueryString from HttpRequest */
	private static final Pattern QUERY_STRING_PATTERN = Pattern.compile("\\?");

	/** Regex to parse out parameters from query string */
	private static final Pattern PARAM_STRING_PATTERN = Pattern.compile("\\&|;");

	/** Regex to parse out key/value pairs */
	private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("=");

	/** Regex to parse raw headers and body */
	private static final Pattern RAW_VALUE_PATTERN = Pattern.compile("\\r\\n\\r\\n");

	/** Regex to parse raw headers from body */
	private static final Pattern HEADERS_BODY_PATTERN = Pattern.compile("\\r\\n");

	/** Regex to parse header name and value */
	private static final Pattern HEADER_VALUE_PATTERN = Pattern.compile(": ");

	/** Regex to split cookie header following RFC6265 Section 5.4 */
	private static final Pattern COOKIE_SEPARATOR_PATTERN = Pattern.compile(";");

	/** Regex to parse raw chunk from body */
	private static final Pattern CHUNK_BODY_PATTERN = Pattern.compile("\\r\\n");

	public HttpDecoder(int type){
		this.type = type;
	}
	
	public boolean consumeToEnd(IoBuffer msg) {
		if(state == null){
			state = State.NEW;
		}
		switch (state) {
		case HEAD:
			LOG.debug("decoding HEAD");
			// grab the stored a partial HEAD request
			// concat the old buffer and the new incoming one
			msg = IoBuffer.allocate(tmpBuffer.remaining() + msg.remaining()).put(tmpBuffer).put(msg).flip();
			// now let's decode like it was a new message
		case NEW:
			LOG.debug("decoding NEW");
			data = IoBuffer.allocate(100);
			data.setAutoExpand(true);
			header = IoBuffer.allocate(100);
			header.setAutoExpand(true);
			body = IoBuffer.allocate(100);
			body.setAutoExpand(true);
			state = null;
			BODY_REMAINING_BYTES = 0;
			tmpBuffer = null;
			httpMessage = null;
			if(this.type==TYPE_REQUEST){
				httpRequest = parseHttpRequestHead(msg);
				httpMessage = httpRequest;
			}
			else{
				httpResponse = parseHttpReponseHead(msg);
				httpMessage = httpResponse;
			}
			
			if (httpMessage==null) {
				// we copy the incoming BB because it's going to be recycled by the inner IoProcessor for next reads
				tmpBuffer = ByteBuffer.allocate(msg.remaining());
				tmpBuffer.put(msg.buf());
				tmpBuffer.flip();
				// no request decoded, we accumulate
				state = State.HEAD;
			} else {
				LOG.debug("decoding content.");
				String contentLen = httpMessage.getHeader("content-length");
				if (contentLen != null) {
					LOG.debug("found content len : {}", contentLen);
					BODY_REMAINING_BYTES = Integer.valueOf(contentLen);
					if(BODY_REMAINING_BYTES==0){
						return true;
					}
					state = State.BODY_WITH_CONTENT_LENGTH;
				} else if ("chunked".equalsIgnoreCase(httpMessage.getHeader("transfer-encoding"))) {
					LOG.debug("no content len but chunked");
					state = State.BODY_CHUNKED_NEW;
				} else if ("close".equalsIgnoreCase(httpMessage.getHeader("connection"))) {
					//                    session.closeNow();
					LOG.info("no content len or chuncked encoding, but connection is specified as close, "
                			+ "so the content-length is the remaining bytes lenght={}", msg.remaining());
                	BODY_REMAINING_BYTES = msg.remaining();
                	state = State.BODY_WITH_CONTENT_LENGTH;
				} else {
					LOG.debug("no content length.");
					consume(ConsumeType.DISCARD,msg,msg.remaining());
					state = State.NEW;
					BODY_REMAINING_BYTES = 0;
					return true;
				}
			}
			break;
		case BODY_WITH_CONTENT_LENGTH:
			LOG.debug("decoding BODY: {} bytes", msg.remaining());
			int dataSize = msg.remaining();
			// do we have reach end of body ?
			int remaining = BODY_REMAINING_BYTES;
			remaining -= dataSize;
			if (remaining <= 0 ) {
				LOG.debug("end of HTTP body");
				consume(ConsumeType.BODY,msg,BODY_REMAINING_BYTES);
				state = State.NEW;
				BODY_REMAINING_BYTES = 0;
				return true;
				//            	out.write(new HttpEndOfContent());
			} else {
				consume(ConsumeType.BODY,msg,dataSize);
				BODY_REMAINING_BYTES = remaining;
			}
			break;
		case BODY_CHUNKED_HEAD:
			LOG.debug("decoding HEAD");
			// grab the stored a partial HEAD request
			// concat the old buffer and the new incoming one
			msg = IoBuffer.allocate(tmpBuffer.remaining() + msg.remaining()).put(tmpBuffer).put(msg).flip();
			// now let's decode like it was a new chunk data
		case BODY_CHUNKED_NEW:
			//refer to https://en.wikipedia.org/wiki/Chunked_transfer_encoding
			int chunkSize = parseChunkSize(msg);
			if(chunkSize==-1){
				//we haven't received full chunk head
				// we copy the incoming BB because it's going to be recycled by the inner IoProcessor for next reads
				tmpBuffer = ByteBuffer.allocate(msg.remaining());
				tmpBuffer.put(msg.buf());
				tmpBuffer.flip();
				// no request decoded, we accumulate
				state = State.BODY_CHUNKED_HEAD;
			}
			else if(chunkSize==0){
				//the end of http body.
				//consume the lasting \r\n
				consume(ConsumeType.DISCARD,msg,2);
				state = State.NEW;
				LOG.debug("all chunks received!");
				return true;
			}
			else{
				BODY_REMAINING_BYTES = chunkSize;
				state = State.BODY_CHUNKED_BODY;
			}
			break;
		case BODY_CHUNKED_BODY:
			LOG.debug("decoding chunk: {} bytes", msg.remaining());
			dataSize = msg.remaining();
			// do we have reach end of body ?
			remaining = BODY_REMAINING_BYTES;
			remaining -= dataSize;
			if (remaining <= 0 ) {
				LOG.debug("end of these chunk");
				consume(ConsumeType.BODY,msg,BODY_REMAINING_BYTES);
				//consume the lasting \r\n
				consume(ConsumeType.DISCARD,msg,2);
				state = State.BODY_CHUNKED_NEW;
				BODY_REMAINING_BYTES = 0;
			} else {
				consume(ConsumeType.BODY,msg,dataSize);
				BODY_REMAINING_BYTES = remaining;
			}
			break;
		}

		return false;
	}

	private int parseChunkSize(IoBuffer buffer) {
		// ************* bug ************
//		final String raw = new String(buffer.array(), buffer.position(), buffer.limit());
		final String raw = new String(buffer.array(), buffer.position(), buffer.remaining());
		final String[] headersAndBody = CHUNK_BODY_PATTERN.split(raw, -1);
		if (headersAndBody.length <= 1) {
			// we didn't receive the full chunk head
			return -1;
		}

		// we put the buffer position where we found the beginning of the chunk body
		//        buffer.position(buffer.position()+headersAndBody[0].length() + 2);
		consume(ConsumeType.DISCARD,buffer,headersAndBody[0].length() + 2);

		// the chunk size is in hex format.
		return Integer.parseInt(headersAndBody[0], 16);
	}

	private void consume(ConsumeType type,IoBuffer msg, int size) {
		byte[] dst = new byte[size];
		msg.get(dst);
		data.put(dst);
		switch(type){
		case DISCARD:
			break;
		case HEADER:
			header.put(dst);
			break;
		case BODY:
			body.put(dst);
			break;
		}
	}

	private HttpRequestImpl parseHttpRequestHead(IoBuffer buffer) {
		final String raw = new String(buffer.array(), buffer.position(), buffer.remaining());
		final String[] headersAndBody = RAW_VALUE_PATTERN.split(raw, -1);
		if (headersAndBody.length <= 1) {
			// we didn't receive the full HTTP head
			return null;
		}
		String[] headerFields = HEADERS_BODY_PATTERN.split(headersAndBody[0]);
		headerFields = ArrayUtil.dropFromEndWhile(headerFields, "");

		final String requestLine = headerFields[0];
		final Map<String, String> generalHeaders = new HashMap<String, String>();

		for (int i = 1; i < headerFields.length; i++) {
			final String[] header = HEADER_VALUE_PATTERN.split(headerFields[i]);
			generalHeaders.put(header[0].toLowerCase(), header[1]);
		}

		final String[] elements = REQUEST_LINE_PATTERN.split(requestLine);
        final HttpMethod method = HttpMethod.valueOf(elements[0]);
        final HttpVersion version = HttpVersion.fromString(elements[2]);
        final String[] pathFrags = QUERY_STRING_PATTERN.split(elements[1]);
        final String requestedPath = pathFrags[0];
        final String queryString = pathFrags.length == 2 ? pathFrags[1] : "";

		// we put the buffer position where we found the beginning of the HTTP body
		//        buffer.position(buffer.position()+headersAndBody[0].length() + 4);
		consume(ConsumeType.HEADER,buffer,buffer.position()+headersAndBody[0].length() + 4);

		return new HttpRequestImpl(version, method, requestedPath,
                queryString, generalHeaders);
	}

	private DefaultHttpResponse parseHttpReponseHead(IoBuffer buffer) {
		final String raw = new String(buffer.array(), buffer.position(), buffer.remaining());
		final String[] headersAndBody = RAW_VALUE_PATTERN.split(raw, -1);
		if (headersAndBody.length <= 1) {
			// we didn't receive the full HTTP head
			return null;
		}

		String[] headerFields = HEADERS_BODY_PATTERN.split(headersAndBody[0]);
		headerFields = ArrayUtil.dropFromEndWhile(headerFields, "");

		final String requestLine = headerFields[0];
		final Map<String, String> generalHeaders = new HashMap<String, String>();

		for (int i = 1; i < headerFields.length; i++) {
			final String[] header = HEADER_VALUE_PATTERN.split(headerFields[i]);
			generalHeaders.put(header[0].toLowerCase(), header[1]);
		}

		final String[] elements = RESPONSE_LINE_PATTERN.split(requestLine);
		HttpStatus status = null;
		final int statusCode = Integer.valueOf(elements[1]);
		for (int i = 0; i < HttpStatus.values().length; i++) {
			status = HttpStatus.values()[i];
			if (statusCode == status.code()) {
				break;
			}
		}
		final HttpVersion version = HttpVersion.fromString(elements[0]);

		// we put the buffer position where we found the beginning of the HTTP body
		//        buffer.position(buffer.position()+headersAndBody[0].length() + 4);
		consume(ConsumeType.HEADER,buffer,buffer.position()+headersAndBody[0].length() + 4);

		return new DefaultHttpResponse(version, status, generalHeaders);
	}
	
	public HttpRequest getHttpRequest() {
		return httpRequest;
	}

	public HttpResponse getHttpResponse() {
		return httpResponse;
	}

	public IoBuffer getHeader() {
		return header;
	}

	public void setHeader(IoBuffer header) {
		this.header = header;
	}

	public IoBuffer getBody() {
		return body;
	}

	public void setBody(IoBuffer body) {
		this.body = body;
	}

	public IoBuffer getData() {
		return data;
	}

	public void setData(IoBuffer data) {
		this.data = data;
	}

}
