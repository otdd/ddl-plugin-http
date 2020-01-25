package io.otdd.ddl.http;

public class Test {
    public static void main( String[] args ) {
        HttpDDLDecoder decoder = new HttpDDLDecoder();
        byte[] bytes = "HTTP/1.1 200 OK\r\ncontent-length: 48\r\n\r\n{\"id\":0,\"ratings\":{\"Reviewer1\":2,\"Reviewer2\":4}}".getBytes();
        System.out.println(decoder.decodeResponse(bytes,null));
    }
}
