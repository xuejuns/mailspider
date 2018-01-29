package com.gumirov.shamil.partsib;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.gumirov.shamil.partsib.configuration.Configurator;
import com.gumirov.shamil.partsib.configuration.ConfiguratorFactory;
import com.gumirov.shamil.partsib.configuration.endpoints.*;
import com.gumirov.shamil.partsib.configuration.endpoints.Endpoint;
import com.gumirov.shamil.partsib.plugins.Plugin;
import com.gumirov.shamil.partsib.util.PricehookIdTaggingRulesConfigLoaderProvider;
import com.gumirov.shamil.partsib.util.Util;
import com.sun.istack.Nullable;
import org.apache.camel.*;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Rule;

import javax.activation.DataHandler;
import javax.mail.*;
import javax.mail.internet.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Properties;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Abstract AT.
 */
public abstract class AbstractMailAutomationTest extends CamelTestSupport {
  private static final String ENDPID = "Test-EMAIL-01";
  private final int httpPort = 8888;
  private String httpendpoint="/endpoint";
  private final String httpUrl = "http://127.0.0.1:"+ httpPort+httpendpoint;

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(WireMockConfiguration.wireMockConfig().port(httpPort));

  ConfiguratorFactory cfactory = new ConfiguratorFactory(){
    @Override
    protected void initDefaultValues(HashMap<String, String> kv) {
      super.initDefaultValues(kv);
      //next line is to enter condition PricehookIdTaggingRulesLoaderProcessor:27
      kv.put("pricehook.config.url", "http://ANYTHING");
      kv.put("output.url", httpUrl);
      kv.put("email.enabled", "true");
      kv.put("local.enabled", "0");
      kv.put("ftp.enabled",   "0");
      kv.put("http.enabled",  "0");
      kv.put("endpoints.config.filename", "target/classes/test_local_endpoints.json");
      kv.put("email.accept.rules.config.filename=", "src/main/resources/email_accept_rules.json");
    }
  };
  Configurator config = cfactory.getConfigurator();

  MainRouteBuilder builder;

  @EndpointInject(uri = "mock:result")
  protected MockEndpoint mockEndpoint;

  @Produce(uri = "direct:start")
  protected ProducerTemplate template;
  private AttachmentVerifier attachmentVerifier;

  @Override
  public boolean isUseAdviceWith() {
    return true;
  }

  /**
   * This impl removes real imap endpoint. Override to change.
   */
  public void beforeLaunch() throws Exception {
    //remove imap endpoint
    context.getRouteDefinition("source-"+getEndpointName()).adviceWith(context, new AdviceWithRouteBuilder() {
      @Override
      public void configure() throws Exception {
        replaceFromWith("direct:none");
      }
    });

    //http mock endpoint setup
    stubFor(post(urlEqualTo(httpendpoint))
        .willReturn(aResponse()
            .withStatus(200)));
  }

  public AbstractMailAutomationTest setAttachmentVerifier(AttachmentVerifier verifier) {
    this.attachmentVerifier = verifier;
    return this;
  }

  /**
   * Call this to start test.
   * More useful args list. Just proxy to another launch().
   */
  public void launch(String route, String id, List<String> expectTags, List<String> expectNames,
                     int expectNumTotal, String sendToEndpoint, @Nullable EmailMessage...msgs) throws Exception {
    HashMap<EmailMessage, String> map = null;
    if (msgs != null) {
      map = new HashMap<>();
      for (EmailMessage m : msgs)
        map.put(m, sendToEndpoint);
    }
    launch(route, id, expectTags, expectNames, expectNumTotal, map);
  }

  public void launch(String route, String id, List<String> expectTags, List<String> expectNames,
              int expectNumTotal, @Nullable Map<EmailMessage, String> toSend) throws Exception {
    context.getRouteDefinition(route).adviceWith(context, new AdviceWithRouteBuilder() {
      @Override
      public void configure() throws Exception {
        weaveById(id).after().to(mockEndpoint);
      }
    });

    beforeLaunch();

    if (expectNames != null && expectNumTotal != expectNames.size() ||
        expectTags != null && expectTags.size() != expectNumTotal)
      throw new IllegalArgumentException("Illegal arguments: must be same size of expected tags/names and number of messages");

    if (expectTags != null) mockEndpoint.expectedHeaderValuesReceivedInAnyOrder(MainRouteBuilder.PRICEHOOK_ID_HEADER, expectTags.toArray());
    if (expectNames != null) mockEndpoint.expectedHeaderValuesReceivedInAnyOrder(Exchange.FILE_NAME, expectNames.toArray());
    mockEndpoint.expectedMessageCount(expectNumTotal);

    context.setTracing(isTracing());
    context.start();

    if (toSend != null) sendMessages(toSend);

    waitForCompletion();

    log.info("Expecting {} messages", expectNumTotal);
    mockEndpoint.assertIsSatisfied();

    WireMock.verify(
        WireMock.postRequestedFor(urlPathEqualTo(httpendpoint))
    );

    Map<String, InputStream> atts = new HashMap<>();
    List<LoggedRequest> reqs = WireMock.findAll(postRequestedFor(urlPathEqualTo(httpendpoint)));
    for (LoggedRequest req : reqs) {
      String fname = req.getHeader("X-Filename");
      atts.put(fname, new ByteArrayInputStream(req.getBody()));
    }

    if (attachmentVerifier != null) assertTrue(attachmentVerifier.verify(atts));

    context.stop();
  }

  /**
   * To be overriden for test.
   * @throws Exception
   */
  public abstract void test() throws Exception;

  public Boolean isTracing() {
    return true;
  }


  /**
   * Override to implement sleep before asserting results.
   */
  public void waitForCompletion() {
  }

  /**
   * Override this in subclasses to change default sending behaviour.
   * @param toSend messages list to send.
   */
  public void sendMessages(Map<EmailMessage, String> toSend) {
    for (EmailMessage m : toSend.keySet()) {
      HashMap h = new HashMap(){{put("Subject", m.subject); put("From", m.from);}};
      template.send(toSend.get(m), exchange -> {
        exchange.getIn().setHeaders(h);
        for (String fname : m.attachments.keySet()) {
          exchange.getIn().addAttachment(fname, m.attachments.get(fname));
        }
      });
    }
  }

  public class RawEmailMessage extends EmailMessage {
    public RawEmailMessage(InputStream is) throws MessagingException, IOException {
      super(null);
      Session ses = Session.getDefaultInstance(new Properties());
      MimeMessage msg = new MimeMessage(ses, is);
      subject = msg.getSubject();
      attachments = new HashMap<>();
      from = msg.getFrom()[0].toString();
      String disposition = msg.getDisposition();
      if (disposition != null && disposition.contains("attachment")){
        //Extract attachment filename from headers
        String filename = msg.getFileName();
        //We need not only main header value, but parameters. They are not parsed my MimeMessage, so we do it here manually
        String[] headers = {"Content-Type", "Content-type", "Content-Disposition", "Content-disposition"};
        String[] params = {"name", "filename"};
        for (int i = 0; filename == null && i < headers.length; ++i){
          String[] headerValues = msg.getHeader(headers[i]);
          if (headerValues == null || headerValues.length == 0)
            continue;
          for (int z = 0; filename == null && z < headerValues.length; ++z) {
            for (int j = 0; filename == null && j < params.length; ++j) {
              if (headers[i].toLowerCase().contains("type")){
                try {
                  filename = new ContentType(headerValues[z]).getParameter(params[j]);
                }catch(ParseException e){
                  log.debug("cannot parse: header='"+headers[i]+"' val='"+headerValues[z]+"'", e);
                  continue;
                }
              } else {
                try {
                  filename = new ContentDisposition(headerValues[z]).getParameter(params[j]);
                }catch(ParseException e){
                  log.debug("cannot parse: header='"+headers[i]+"' val='"+headerValues[z]+"'", e);
                  continue;
                }
              }
            }
          }
        }
        filename = MimeUtility.decodeText(filename);
        if (filename != null) {
          DataHandler dh = new DataHandler(msg.getContent(), msg.getContentType());
          attachments.put(filename, dh);
        }
      } else {
        handleMessage(msg);
      }
    }

    public void handleMessage(javax.mail.Message message) throws IOException, MessagingException {
      Object content = message.getContent();
      String contentType = message.getContentType();
      if (content instanceof String ) {
        throw new RuntimeException("not yet impl");
//        attachments.put(bp.getFileName(), new DataHandler(content, "text/plain"));
//        Session ses = Session.getDefaultInstance(new Properties());
//        MimeMessage msg = new MimeMessage(ses, new StringBufferInputStream((String) content));
//        this.subject = msg.getSubject();
//        handleMessage(msg);
      } else if (content instanceof Multipart) {
        Multipart mp = (Multipart) content;
        handleMultipart(mp);
      } else {
        throw new RuntimeException("not yet impl");
      }
    }

    public void handleMultipart(Multipart mp) throws MessagingException, IOException {
      int count = mp.getCount();
      for (int i = 0; i < count; i++) {
        BodyPart bp = mp.getBodyPart(i);
        Object content = bp.getContent();
        if (content instanceof String) {
          attachments.put(bp.getFileName(), new DataHandler(content, bp.getContentType()));
        } else if (content instanceof InputStream) {
          attachments.put(bp.getFileName(), new DataHandler(Util.readFully((InputStream) content),
              bp.getContentType()));
        } else if (content instanceof javax.mail.Message) {
          handleMessage((javax.mail.Message) content);
        } else if (content instanceof Multipart) {
          handleMultipart((Multipart) content);
        } else {
          log.error("Cannot process message content: class=" + content.getClass());
          throw new RuntimeException("not yet impl");
        }
      }
    }
  }

  public class EmailMessage {
    String subject;
    Map<String, DataHandler> attachments;
    public String from;

    public EmailMessage(String subject, String from, Map<String, DataHandler> attachments) {
      this.subject = subject;
      this.from = from;
      this.attachments = attachments;
    }

    public EmailMessage(String subject, List<String> attachmentNames) {
      this.subject = subject;
      this.attachments = new HashMap<>();
      InputStream is = new ByteArrayInputStream(new byte[]{'1','2','3','4','5','6','7','8','9','0'});
      for (String fname : attachmentNames) {
        attachments.put(fname, new DataHandler(is, "text/plain"));
      }
    }

    public EmailMessage(String subject) {
      this.subject = subject;
    }
  }

  @Override
  protected RoutesBuilder createRouteBuilder() throws Exception {
    builder = new MainRouteBuilder(getConfigurator()){
      @Override
      public List<PricehookIdTaggingRule> getPricehookConfig() throws IOException {
        return getTagRules();
      }

      @Override
      public PricehookIdTaggingRulesConfigLoaderProvider getConfigLoaderProvider() {
        return url -> getPricehookConfig();
      }

      @Override
      public ArrayList<EmailAcceptRule> getEmailAcceptRules() throws IOException {
        return getAcceptRules();
      }

      @Override
      public List<Plugin> getPlugins() {
        return getPluginsList();
      }

      @Override
      public Endpoints getEndpoints() throws IOException {
        Endpoints e = new Endpoints();
        e.ftp=new ArrayList<>();
        e.http=new ArrayList<>();
        e.email = new ArrayList<>();
        Endpoint email = getEndpoint();
        e.email.add(email);
        return e;
      }
    };
    return builder;
  }

  protected List<Plugin> getPluginsList() {
    return null;
  }

  /**
   * Override this method to change config values.
   * @return config
   */
  protected Configurator getConfigurator() {
    return config;
  }

  /**
   * Override to create rules, this implementation accepts any letter with '@' in "From".
   */
  public ArrayList<EmailAcceptRule> getAcceptRules() {
    ArrayList<EmailAcceptRule> rules = new ArrayList<>();
    EmailAcceptRule r = new EmailAcceptRule();
    r.header="From";
    r.contains="@";
    rules.add(r);
    return rules;
  }

  public static List<PricehookIdTaggingRule> loadTagsFile(String filename) {
    try {
      String json = new String(Util.readFully(
          AbstractMailAutomationTest.class.getClassLoader().getResourceAsStream(filename)), "UTF-8");
      List<PricehookIdTaggingRule> list = MainRouteBuilder.parseTaggingRules(json);
      return list;
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  /**
   * Override this if you use external server
   */
  public Endpoint getEndpoint(){
    Endpoint email = new Endpoint();
    email.id = getEndpointName();
    email.url = "imap.example.com";
    email.user = "email@a.com";
    email.pwd = "pwd";
    email.delay = "5000";
    return email;
  }

  /**
   * Use this method's return value when redefining route or override this method.
   */
  public String getEndpointName() {
    return ENDPID;
  }

  public abstract List<PricehookIdTaggingRule> getTagRules();

  @Override
  protected int getShutdownTimeout() {
    return 60;
  }

  @Override
  public boolean isDumpRouteCoverage() {
    return true;
  }

  @Override
  protected boolean useJmx() {
    return true;
  }
}

interface AttachmentVerifier {
  boolean verify(Map<String, InputStream> attachments);
}