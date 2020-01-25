package io.otdd.ddl.http;

import java.util.HashMap;
import java.util.Map;

import org.pf4j.Extension;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

import io.otdd.ddl.plugin.DDLCodecFactory;
import io.otdd.ddl.plugin.DDLDecoder;
import io.otdd.ddl.plugin.DDLEncoder;

public class HttpDDLCodecFactoryPlugin extends Plugin {

	public HttpDDLCodecFactoryPlugin(PluginWrapper wrapper) {
		super(wrapper);
	}
	
	@Extension
	public static class HttpDDLCodecFactory implements DDLCodecFactory{

		private DDLDecoder decoder = new HttpDDLDecoder();
		private DDLEncoder encoder = new HttpDDLEncoder();

		public Map<String,String> getReqProtocolSettings(){
			return null;
		}
		public Map<String,String> getRespProtocolSettings(){
			return null;
		}
		
		public DDLDecoder getDecoder() {
			return decoder;
		}

		public DDLEncoder getEncoder() {
			return encoder;
		}

		public String getProtocolName() {
			return "http";
		}
		
		public String getPluginName() {
			return "http";
		}

		public boolean init(Map<String,String> reqProtocolSettings,
				Map<String,String> respProtocolSettings) {
			return true;
		}
		
		@Override
		public boolean updateSettings(Map<String, String> reqProtocolSettings,
				Map<String, String> respProtocolSettings) {
			return true;
		}

		public void destroy() {
			
		}
		
	}
}
