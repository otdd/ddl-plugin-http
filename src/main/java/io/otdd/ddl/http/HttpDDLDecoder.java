package io.otdd.ddl.http;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

import org.apache.mina.core.buffer.IoBuffer;

import io.otdd.ddl.plugin.DDLDecoder;

public class HttpDDLDecoder implements DDLDecoder {

	public String decodeRequest(byte[] bytes,Map<String,String> protocolSettings) {
		if(!isValidRequest(bytes)){
			return null;
		}
		HttpDecoder decoder = new HttpDecoder(HttpDecoder.TYPE_REQUEST);
		return decode(decoder,bytes,"[request-line]");
	}

	public String decodeResponse(byte[] bytes,Map<String,String> protocolSettings) {
		if(!isValidResponse(bytes)){
			return null;
		}
		HttpDecoder decoder = new HttpDecoder(HttpDecoder.TYPE_RESPONSE);
		return decode(decoder,bytes,"[status-line]");
	}
	
	private boolean isValidRequest(byte[] bytes) {
		ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
		BufferedReader bfReader = new BufferedReader(new InputStreamReader(stream));
		try {
			String firstLine = bfReader.readLine();
			if(firstLine==null||firstLine.isEmpty()){
				return false;
			}
			String[] fields = firstLine.split(" ");
			String lastField = fields[fields.length-1];
			if(lastField.toLowerCase().startsWith("http")){
				return true;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return false;
	}
	
	private boolean isValidResponse(byte[] bytes) {
		ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
		BufferedReader bfReader = new BufferedReader(new InputStreamReader(stream));
		try {
			String firstLine = bfReader.readLine();
			if(firstLine==null||firstLine.isEmpty()){
				return false;
			}
			if(firstLine.toLowerCase().startsWith("http")){
				return true;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	private String decode(HttpDecoder decoder,byte[] bytes,String name){
		IoBuffer buf = IoBuffer.allocate(bytes.length, false);
		buf.put(bytes);
		buf.flip();
		while(buf.hasRemaining()){
			if(decoder.consumeToEnd(buf)){
				StringBuilder sb = new StringBuilder();
				IoBuffer header = decoder.getHeader();
				header.flip();
				if(header.remaining()>0){
					byte[] dst = new byte[header.remaining()];
					header.get(dst);
					String[] tmp = new String(dst).split("\n");
					sb.append(name+"\n"+tmp[0].trim()+"\n\n");
					sb.append("[header]\n");
					for(int i=1;i<tmp.length;i++){
						if(tmp[i].trim().length()>0){
							sb.append(tmp[i].trim()+"\n");
						}
					}
				}

				IoBuffer body = decoder.getBody();
				body.flip();
				if(body.remaining()>0){
					byte[] dst= new byte[body.remaining()];
					body.get(dst);
					if(sb.length()>0){
						sb.append("\n");
					}
					sb.append("[body]\n"+new String(dst));
				}
				return sb.toString();
			}
		}
		return null;
	}

}
