package com.gumirov.shamil.partsib.test;

import org.apache.camel.*;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.dataformat.zipfile.ZipSplitter;
import org.apache.camel.processor.idempotent.FileIdempotentRepository;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.apache.camel.builder.ExpressionBuilder.append;

public class UnzipSplitterTest  extends CamelTestSupport {

  @EndpointInject(uri = "mock:result")
  protected MockEndpoint resultEndpoint;

  @Produce(uri = "direct:from")
  protected FluentProducerTemplate template;
  
  private final String body = "file text contents";
  private final byte[] contents = body.getBytes();

  @Test
  public void testSendMatchingMessage() throws Exception {

    resultEndpoint.expectedMessageCount(2);
    resultEndpoint.expectedBodiesReceivedInAnyOrder(new Object[]{body, body});
    resultEndpoint.expectedHeaderValuesReceivedInAnyOrder(Exchange.FILE_NAME, new Object[]{"f1.txt", "dir/f2.txt"});

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ZipOutputStream zos = new ZipOutputStream(bos);
    zos.putNextEntry(new ZipEntry("f1.txt"));
    zos.write(contents);
    zos.closeEntry();
    zos.putNextEntry(new ZipEntry("dir/f2.txt"));
    zos.write(contents);
    zos.closeEntry();
    zos.flush();
    zos.close();
    
    bos.close();
    byte[] zip = bos.toByteArray();
    System.out.println("zip.length="+zip.length);
    
    template.to("direct:from").withBody(zip).
        withHeader("CamelFileName", "archive.zip").
        withHeader("CamelFileLength", zip.length).
        send();

    resultEndpoint.assertIsSatisfied();
  }

  @Before
  public void setup() {
    File idempotentRepo = new File("target/idempotent_repo.dat");
    if (idempotentRepo.exists()) {
      idempotentRepo.delete();
      log.info("removed repo");
    }
  }

  @Override
  protected RoutesBuilder createRouteBuilder() throws Exception
  {
    return new RouteBuilder()
    {
      @Override
      public void configure() throws Exception
      {
        from("direct:from").
            split(new ZipSplitter()).streaming().
            to("mock:result").
            convertBodyTo(String.class).to("log:RESULT?showBody=true");
      }
    };
  }
}
