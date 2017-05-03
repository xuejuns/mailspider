package com.gumirov.shamil.partsib;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.gumirov.shamil.partsib.configuration.Configurator;
import com.gumirov.shamil.partsib.configuration.ConfiguratorFactory;
import com.gumirov.shamil.partsib.configuration.endpoints.EmailAcceptRule;
import com.gumirov.shamil.partsib.configuration.endpoints.Endpoint;
import com.gumirov.shamil.partsib.configuration.endpoints.Endpoints;
import com.gumirov.shamil.partsib.configuration.endpoints.PricehookIdTaggingRule;
import com.gumirov.shamil.partsib.plugins.NoOpPlugin;
import com.gumirov.shamil.partsib.plugins.Plugin;
import com.icegreen.greenmail.junit.GreenMailRule;
import com.icegreen.greenmail.server.AbstractServer;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.*;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.activation.DataHandler;
import javax.mail.*;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import javax.mail.search.FlagTerm;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.Security;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Automation FTP endpoint test with local FTP daemon
 */
public class EmailRouteATest extends CamelTestSupport {
  private static final String pricehookId = "1.2.0.1";
  final int httpPort = 8888;
  public String httpendpoint="/endpoint";
  final String httpUrl = "http://127.0.0.1:"+ httpPort+httpendpoint;
  private int imapport = 3143;
  final String imapUrl = "imap://127.0.0.1"+":"+imapport;
  private List<String> filenames = Arrays.asList("=?koi8-r?B?8NLBytMg6e7l9DUueGxz?=", "Прайс лист1.csv", "wrongfile.jpg");
  private byte[] contents = "a,b,c,d,e,1,2,3".getBytes();
  final String login = "login-id", pwd = "password", to = "partsibprice@mail.ru";
  { //ssl init
    Security.setProperty("ssl.SocketFactory.provider", DummySSLSocketFactory.class.getName());
  }

  @Rule
  public final GreenMailRule greenMail = new GreenMailRule(ServerSetupTest.IMAP);

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(WireMockConfiguration.wireMockConfig().port(httpPort));

  ConfiguratorFactory cfactory = new ConfiguratorFactory(){
    @Override
    protected void initDefaultValues(HashMap<String, String> kv) {
      super.initDefaultValues(kv);
      kv.put("email.enabled", "true");
      kv.put("local.enabled", "0");
      kv.put("ftp.enabled",   "0");
      kv.put("http.enabled",  "0");
      kv.put("output.url", httpUrl);
      kv.put("endpoints.config.filename", "target/classes/test_local_endpoints.json");
      kv.put("email.accept.rules.config.filename=", "src/main/resources/email_accept_rules.json");
    }
  };
  Configurator config = cfactory.getConfigurator();

  @Before
  public void setup() throws Exception {
    //disable ssl cert checking for imaps connections
    Security.setProperty("ssl.SocketFactory.provider", DummySSLSocketFactory.class.getName());
    context.start();
  }

  public void prepareHttpdOK() {
    stubFor(post(urlEqualTo(httpendpoint))
        .willReturn(aResponse()
            .withStatus(200)));
  }
  
  private void prepareHttpdFailFirstNTimes(int n) {
    stubFor(post(urlEqualTo(httpendpoint)).inScenario("fail")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse()
            .withStatus(500).withStatusMessage("Internal server error"))
        .willSetStateTo("STATE_"+1));
    for (int i = 1; i < n; ++i) {
      stubFor(post(urlEqualTo(httpendpoint)).inScenario("fail")
          .whenScenarioStateIs("STATE_"+i)
          .willReturn(aResponse()
              .withStatus(500).withStatusMessage("Internal server error"))
          .willSetStateTo( i < n-1 ?("STATE_"+(i+1)) : "OK"));
    }
    stubFor(post(urlEqualTo(httpendpoint)).inScenario("fail")
        .whenScenarioStateIs("OK")
        .willReturn(aResponse()
            .withStatus(200)));
  }

  @Test
  public void test() throws Exception{
//    WireMock.reset();
    prepareHttpdOK();
    execute(() -> sendMessage(filenames), 
        10000,
        validate(filenames.get(0), 1, pricehookId),
        validate(filenames.get(1), 1, pricehookId),
        () -> verify(2, postRequestedFor(urlEqualTo(httpendpoint))),
        () -> {
          UnseenRetriever unseenRetriever = new UnseenRetriever(greenMail.getImap());
          Message[] messages = unseenRetriever.getMessages(login, pwd);
          assertEquals(0, messages.length);
        }
    );
  }

  @Test
  public void testWithHttpFailures() throws Exception{
//    WireMock.reset();
    prepareHttpdFailFirstNTimes(2);
    execute(() -> sendMessage(filenames),
        5000,
        validate(filenames.get(0), 1, pricehookId),
        validate(filenames.get(1), 1, pricehookId),
        () -> verify(2, postRequestedFor(urlEqualTo(httpendpoint))),
        () -> {
          //TRANSACTION: deleted processed message
          UnseenRetriever unseenRetriever = new UnseenRetriever(greenMail.getImap());
          Message[] messages = unseenRetriever.getMessages(login, pwd);
          assertEquals(1, messages.length);
        }
    );
  }

  void execute(Runnable test, long timeWait, Runnable ... validators) throws InterruptedException {
    test.run();
    Thread.sleep(timeWait);
    for (Runnable r : validators) {
      r.run();
    }
  }

  public Runnable validate(String filename, int parts, String pricehookId) {
    return () -> {
//      assertTrue(getAllServeEvents().size() > 0);
      try {
        for (int i = 0; i < parts; ++i) {
          verify(
              postRequestedFor(urlEqualTo(httpendpoint))
                  .withHeader("X-Filename", equalTo(Base64.getEncoder().encodeToString(filename.getBytes("UTF-8"))))
                  .withHeader("X-Pricehook", equalTo(pricehookId))
                  .withHeader("X-Part", equalTo(""+i))
                  .withHeader("X-Parts-Total", equalTo(""+parts))
          );
        }
      } catch (UnsupportedEncodingException e) {
        e.printStackTrace();
      }
    };
  }

  private void sendMessage(List<String> filenames) {
    GreenMailUser user = greenMail.setUser(to, login, pwd);
    HashMap<String, byte[]> attach = new HashMap<>();
    try{
      for (String fn : filenames) {
        attach.put(MimeUtility.encodeText(fn), contents);
      }
      user.deliver(createMimeMessage(to, "someone@gmail.com","Прайс-лист компании ASVA Re", attach)); }
    catch (Exception e){throw new RuntimeException(e);}
  }

  private MimeMessage createMimeMessage(String to, String from, String subject, Map<String, byte[]> attachments) throws MessagingException {
    MimeMessage msg = GreenMailUtil.createTextEmail(to, from, subject, "body", greenMail.getImap().getServerSetup());
    Multipart multipart = new MimeMultipart();
    for (String fname : attachments.keySet()) {
      MimeBodyPart messageBodyPart = new MimeBodyPart();
      byte[] file = attachments.get(fname);
      messageBodyPart.setDataHandler(new DataHandler(file, "application/vnd.octet-stream"));
      messageBodyPart.setFileName(fname);
      multipart.addBodyPart(messageBodyPart);
    }
    msg.setContent(multipart);
    return msg;
  }

  @Override
  public boolean isUseAdviceWith() {
    return true;
  }

  @Override
  protected RoutesBuilder createRouteBuilder() throws Exception {
    return new MainRouteBuilder(config){
      @Override
      public List<Plugin> getPlugins() {
        return Arrays.asList(new NoOpPlugin());
      }

      @Override
      public Endpoints getEndpoints() throws IOException {
        Endpoints e = new Endpoints();
        e.ftp=new ArrayList<>();
        e.http=new ArrayList<>();
        e.email = new ArrayList<>();
        Endpoint email = new Endpoint();
        email.id = "Test-EMAIL-01";

        email.url = imapUrl;
        email.user = login;
        email.pwd = pwd;

        email.delay = "10000";
        e.email.add(email);
        return e;
      }

      @Override
      public ArrayList<EmailAcceptRule> getEmailAcceptRules() throws IOException {
        ArrayList<EmailAcceptRule> rules = new ArrayList<>();
        EmailAcceptRule r1 = new EmailAcceptRule();
        r1.header="Subject";
        r1.contains="Re";
        rules.add(r1);
        return rules;
      }

      @Override
      public List<PricehookIdTaggingRule> getPricehookConfig() throws IOException {
        PricehookIdTaggingRule r1 = new PricehookIdTaggingRule();
        r1.header = "Subject";
        r1.contains = "ASVA";
        r1.pricehookid = pricehookId;
        PricehookIdTaggingRule r2 = new PricehookIdTaggingRule();
        r2.header = "Subject";
        r2.contains = "АСВА";
        r2.pricehookid = pricehookId;
        PricehookIdTaggingRule r3 = new PricehookIdTaggingRule();
        r3.header = "Subject";
        r3.contains = "Прайс";
        r3.pricehookid = pricehookId;
        return Arrays.asList(r1, r2, r3);
      }
    };
  }
}

