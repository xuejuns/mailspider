package com.gumirov.shamil.partsib;

import com.gumirov.shamil.partsib.configuration.endpoints.PricehookIdTaggingRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * (c) 2017 by Shamil Gumirov (shamil@gumirov.com).<br/>
 * Date: 6/6/2017 Time: 09:47<br/>
 */
public class YahooRawMailTest extends AbstractMailAutomationTest {

  @Test
  public void test() throws Exception {
    launch("acceptedmail", "taglogger",
        Arrays.asList("977.0.msk"),
        null, 1, "direct:emailreceived",
        new RawEmailMessage(getClass().getClassLoader().getResourceAsStream("double_quotes_bad.eml"))
    );
  }

  @Override
  public List<PricehookIdTaggingRule> getTagRules() {
    return loadTagsFile("partsib_tags_config2.json");
  }
}