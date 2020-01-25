package io.otdd.ddl.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.otdd.ddl.plugin.DDLEncoder;

public class HttpDDLEncoder implements DDLEncoder {

	public byte[] encodeRequest(String ddl,Map<String,String> protocolSettings) {
		return encode(ddl,"[request-line]","");
	}
	
	public byte[] encodeResponse(String ddl,Map<String,String> protocolSettings) {
		return encode(ddl,"[status-line]","HTTP/1.1 200 OK");
	}
	
	public byte[] encode(String ddl,String name,String defaultValue) {
		
		String[] lines = ddl.replaceAll("\r", "").split("\n");
		int status = 0;
		String statusLine = defaultValue;
		List<String> headers = new ArrayList<String>();
		StringBuilder body = new StringBuilder();
		for(String tmp:lines){
			String line = tmp.trim();
			if(line.length()==0){
				continue;
			}
			if(line.equals(name)){
				status = 1;
				continue;
			}
			if(line.equals("[header]")){
				status = 2;
				continue;
			}
			if(line.equals("[body]")){
				status = 3;
				continue;
			}
			
			switch(status){
			case 1:
				statusLine = line.trim();
				break;
			case 2:
				if(!line.toLowerCase().contains("content-length")&&
						!line.toLowerCase().contains("connection:close")&&
						!line.toLowerCase().contains("transfer-encoding")){
					headers.add(line);
				}
				break;
			case 3:
				body.append(line+"\r\n");
				break;
			}
		}
		
		statusLine = removeMultipleSpaces(statusLine);
		if(statusLine.length()==0){
			return null;
		}
		
		headers.add("Content-Length: "+body.toString().trim().getBytes(StandardCharsets.UTF_8).length);
		
		StringBuilder resp = new StringBuilder();
		resp.append(statusLine+"\r\n");
		for(String header:headers){
			resp.append(header+"\r\n");
		}
		resp.append("\r\n");
		if(body.length()>0){
			resp.append(body.toString().trim());
		}
		return resp.toString().getBytes(StandardCharsets.UTF_8);
	}

	private String removeMultipleSpaces(String statusLine) {
		String[] tmp = statusLine.split(" ");
		StringBuilder tmpSB = new StringBuilder();
		for(String tmpField:tmp){
			if(tmpField.trim().length()>0){
				tmpSB.append(tmpField.trim()+" ");
			}
		}
		return tmpSB.toString().trim();
	}
}
