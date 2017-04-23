package com.gumirov.shamil.partsib.processors;

import com.gumirov.shamil.partsib.MainRouteBuilder;
import com.gumirov.shamil.partsib.configuration.endpoints.PricehookIdTaggingRule;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.SimpleBuilder;

import java.io.IOException;
import java.util.List;

/**
 * This processor sets 'tag' header for excehange. Applies to single file message.
 * Configures with a list of tagging rules. Uses 2 types of rules: static and dynamic. Static rules are specified via
 * config (pricehook.tagging.config.filename),
 * dynamic is taken from exchange from header by name MainRouteBuilder.PRICEHOOK_TAGGING_RULES_HEADER.
 * By default, dynamic rules if exist replaces static (which means if at least one dynamic rule exists, the static rules
 * are forgotten).
 */
public class PricehookTaggerProcessor implements Processor {
  List<PricehookIdTaggingRule> rules;
  
  public PricehookTaggerProcessor(List<PricehookIdTaggingRule> rules) throws IOException {
    this.rules = rules;
    for (PricehookIdTaggingRule rule : rules) {
      rule.predicate = SimpleBuilder.simple("${in.header."+rule.header+"} contains \""+rule.contains+"\"");
    }
  }

  @Override
  public void process(Exchange exchange) throws Exception {
    List<PricehookIdTaggingRule> rulesDynamic = (List<PricehookIdTaggingRule>) exchange.getIn().getHeader(MainRouteBuilder.PRICEHOOK_TAGGING_RULES_HEADER);
    if (rulesDynamic != null) rules  = rulesDynamic;
    if (rules == null) throw new Exception("FATAL: No pricehook id tagging rules");
    for (PricehookIdTaggingRule rule : rules){
      if (rule.predicate.matches(exchange)) {
        exchange.getIn().setHeader(MainRouteBuilder.PRICEHOOK_ID_HEADER, rule.pricehookid);
      }
    }
  }
}
