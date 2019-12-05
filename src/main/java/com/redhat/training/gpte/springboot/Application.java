/*
 * Copyright 2016 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */
package com.redhat.training.gpte.springboot;

import org.apache.activemq.jms.pool.PooledConnectionFactory;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.amqp.AMQPComponent;
import org.apache.camel.component.servlet.CamelHttpTransportServlet;
import org.apache.camel.dataformat.bindy.csv.BindyCsvDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportResource;

@SpringBootApplication
// load regular Spring XML file from the classpath that contains the Camel XML DSL
@ImportResource({"classpath:spring/camel-context.xml"})
public class Application extends RouteBuilder{

    /**
     * A main method to start this application.
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    @Override
    public void configure () throws Exception {
    	
	  onException(IllegalArgumentException.class)
	  	.log(LoggingLevel.INFO, "org.fuse.usecase", "onException triggered")
	    .to("direct:error")
	    .handled(true)
	    ;
	
	  BindyCsvDataFormat format = new BindyCsvDataFormat(org.acme.Customer.class);
	  format.setLocale("default");
	  
	  restConfiguration().component("servlet").port("8080")
	  	.host("localhost");

	  rest("/service")
	  	.post("/customers")
//		  	.to("amqp:queue:inputQueue")
	  	;

	  // ERROR LOGGER
	  from("direct:error")
	  	.log(LoggingLevel.INFO, "org.fuse.usecase", "processing error...")
	  	.process(new Processor() {
			
			@Override
			public void process(Exchange exchange) throws Exception {
				final Throwable ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
				exchange.getIn().setHeader("error-code", "111");
				exchange.getIn().setHeader("error-message", ex.getMessage());
			}
		})
	  	.to("amqp:topic:errorNotification")
	    ;
	  
	  // ERROR HANDLER
	  from("amqp:topic:errorNotification")
	  	  .setExchangePattern(ExchangePattern.InOnly)
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "error triggered from topic")
	      .to("sql:insert into USECASE.T_ERROR (ERROR_CODE, ERROR_MESSAGE, MESSAGE, STATUS) VALUES (:#${headers.error-code}, :#${headers.error-message}, :#${body}, 'ERROR')")
	  	;
	  
	  // CORRECT ERRORS
	  from("sql:select MESSAGE, ID from USECASE.T_ERROR where STATUS = 'FIXED' ?consumer.onConsume=update USECASE.T_ERROR set STATUS='CLOSE' where ID = :#ID")
  		  .split(body())
	  	  .log(LoggingLevel.INFO, "org.fuse.usecase", "error fixed")
	  	  .setBody().simple("${body[message]}")
	  	  .to("amqp:queue:inputQueue")
	  	;
	  
	  // SPLIT and TRANSFORM
//	  from("amqp:queue:inputQueue")
	  from("direct:input")
	  	  .setExchangePattern(ExchangePattern.InOnly)
	      .split()
	      .tokenize(";")
	      .log(LoggingLevel.INFO, "org.fuse.usecase", "msg tokenized")
	      .unmarshal(format)
	      .log(LoggingLevel.INFO, "org.fuse.usecase", "msg unmarshalled")
	      .to("dozer:customerToAccount?mappingFile=transformation.xml&sourceModel=org.acme.Customer&targetModel=org.globex.Account")
	      .log(LoggingLevel.INFO, "org.fuse.usecase", "msg ran through dozer")
	      .marshal().json(JsonLibrary.Jackson)
	      .to("amqp:queue:accountQueue")
	      ;
	  
    }
    
    @Bean
    ServletRegistrationBean servletRegistrationBean() {
        ServletRegistrationBean servlet = new ServletRegistrationBean(
            new CamelHttpTransportServlet(), "/rest/*");
        servlet.setName("CamelServlet");
        return servlet;
    }

    @Bean(name = "amqp-component")
    AMQPComponent amqpComponent(AMQPConfiguration config) {
        JmsConnectionFactory qpid = new JmsConnectionFactory(config.getUsername(), config.getPassword(), "amqp://"+ config.getHost() + ":" + config.getPort());
        //qpid.setTopicPrefix("topic://");

        PooledConnectionFactory factory = new PooledConnectionFactory();
        factory.setConnectionFactory(qpid);

        return new AMQPComponent(factory);
    }
}