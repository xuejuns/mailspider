package com.gumirov.shamil.partsib;

import com.gumirov.shamil.partsib.configuration.endpoints.PricehookIdTaggingRule;
import com.gumirov.shamil.partsib.plugins.Plugin;
import com.gumirov.shamil.partsib.util.Util;
import com.partsib.mailspider.plugins.ExcelToCsvConverterPlugin;
import org.junit.Test;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.util.ByteArrayDataSource;
import java.util.*;

/**
 * @author Shamil@Gumirov.com
 * Copyright (c) 2017 by Shamil Gumirov.
 */
public class EncodingTest extends AbstractMailAutomationTest {
  @Test
  public void test() throws Exception {
    Map<String, DataHandler> attachment = new HashMap<>();
    final String name = "944-xtr.xlsx", ct = "application/vnd.ms-excel", tag = "944.0.main";
    final String contents = "\"Table 1\"\n" +
        "\n" +
        ",\"привет\",\"по \",\"русски!\",\"трололо :)\"";
    byte[] d = Util.readFully(getClass().getClassLoader().getResourceAsStream("russian_table.xlsx"));
    DataSource ds = new ByteArrayDataSource(d, ct);
    attachment.put(name, new DataHandler(ds));

    setAttachmentVerifier(attachments -> {
      for (String f : attachments.keySet()) {
        try {
          String s = new String(Util.readFully(attachments.get(f)), "UTF-8");
          log.info("Read CSV: '"+s+"'");
          return contents.trim().equals(s.trim());
        } catch (Exception e) {
          log.error("Cannot read data", e);
        }
      }
      return false;
    });

    launch("acceptedmail", "taglogger",
        Collections.singletonList(tag),
        null, 1, "direct:emailreceived",
        new EmailMessage("subj", "natalia.sh@ivers.ru", attachment)
    );
  }

  @Override
  protected List<Plugin> getPluginsList() {
    return Collections.singletonList(new ExcelToCsvConverterPlugin());
  }

  @Override
  public List<PricehookIdTaggingRule> getTagRules() {
    return loadTagsFile("prod_rules.json");
  }
}