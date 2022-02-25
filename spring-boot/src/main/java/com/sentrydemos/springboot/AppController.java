package com.sentrydemos.springboot;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// import java.net.HttpURLConnection;
// import java.net.URL;
// import java.net.InputStream;
// import java.net.APOD;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


import com.fasterxml.jackson.databind.ObjectMapper;

import io.sentry.IHub;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;
import io.sentry.protocol.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class AppController {

	private final Logger logger = LoggerFactory.getLogger(Application.class);
	private List<String> headerTags = new ArrayList<>();
	
	@Autowired
	private DatabaseHelper dbHelper = new DatabaseHelper();

	@Autowired
	private IHub hub;
	
	// headers passed by frontend
	@Bean
	public void initHeaders() {
		headerTags.add("se");
		headerTags.add("customerType");
		headerTags.add("email");

	}

	private void setTags(HttpServletRequest request) {
		//to print all headers
		/*
		 * logger.info("request header names and vals: "); for (Enumeration<?> e =
		 * request.getHeaderNames(); e.hasMoreElements();) { String nextHeaderName =
		 * (String) e.nextElement(); String headerValue =
		 * request.getHeader(nextHeaderName); logger.info("\t" + nextHeaderName + ", " +
		 * headerValue); }
		 */

		for (String tag : headerTags) {
			logger.info("Checking header for tag: " + tag);
			String header = request.getHeader(tag);
			if (header != null && header != "null") {
				logger.info("Setting " + tag + " Sentry tag as " + header);
				if (tag == "email") {
					User user = new User();
					user.setEmail(header);
					Sentry.setUser(user);
				} else {
					Sentry.setTag(tag, header);
				}
			}
		}

	}
	
	@CrossOrigin
	@GetMapping("/")
	public String index() {
		logger.info("returning from / call: Greetings from Spring Boot!");
		return "Greetings from Spring Boot!";

	}

	@CrossOrigin
	@GetMapping("/success")
	public String Success() {
		logger.info("success");
		return "success from springboot";

	}

	@CrossOrigin
	@GetMapping("/handled")
	public String HandledError() {
		String someLocalVariable = "stack locals";

		try {
			int example = 1 / 0;
		} catch (Exception e) {
			logger.error("caught exception", e);
			return "Fail";
		}
		return "Success";
	}

	@CrossOrigin
	@GetMapping("/unhandled")
	public String UnhandledError() {
		throw new RuntimeException("Unhandled Exception!");
	}

	@CrossOrigin
	@GetMapping("/api")
	public String Api() {
		return "springboot /api";
	}

	@CrossOrigin
	@GetMapping("/connect")
	public String Connect() {
		return "springboot /connect";
	}

	@CrossOrigin
	@GetMapping("/organization")
	public String Organization() {
		return "springboot /organization";
	}

	@CrossOrigin
	@GetMapping("/logback")
	public String Logback() {
		logger.info("info log");
		logger.warn("warn log");
		logger.error("error log");
		return "Made an info, warn, and error log entry.\n"
				+ "Whether they go to Sentry depends on the application.properties value: sentry.logging.minimum-event-level";

	}

	OkHttpClient client = new OkHttpClient();

	@CrossOrigin
	@GetMapping("/products")
	public String GetProductsDelay(HttpServletRequest request) {
		logger.info("> try");
		try {
			run("https://application-monitoring-ruby-dot-sales-engineering-sf.appspot.com/api");
		} catch (IOException e) {
			logger.info("> IOException");
		}
		logger.info("> done");

		ISpan span = hub.getSpan().startChild("Overhead", "Set tags");
		//initInventory(); // initialize inventory each time we hit this endpoint
		setTags(request);
		span.finish();
		String allProducts = dbHelper.mapAllProducts(hub.getSpan());

		return allProducts;
	}

	String run(String url) throws IOException {
		Request request = new Request.Builder()
			.url(url)
			.build();
		logger.info("> run");
		try (Response response = client.newCall(request).execute()) {
			logger.info("> run return");
		  return response.body().string();
		}
	  }

	@CrossOrigin
	@GetMapping("/products-join")
	public String GetProducts(HttpServletRequest request) {
		ISpan span = hub.getSpan().startChild("Overhead", "Set tags");
		setTags(request);
		span.finish();
		String allProducts = dbHelper.mapAllProductsJoin(hub.getSpan());
		return allProducts;
	}

	@CrossOrigin
	@PostMapping("/checkout")
	public String CheckoutCart(HttpServletRequest request, @RequestBody String payload) throws Exception {
		ISpan span = hub.getSpan().startChild("Overhead", "Set tags and map payload to Cart object");
		setTags(request);
		
		JSONObject json = new JSONObject(payload);
		
		ObjectMapper objectMapper = new ObjectMapper();

		Cart cart = objectMapper.readValue(json.get("cart").toString(), Cart.class);

		span.finish();
		
		ISpan checkoutSpan = hub.getSpan().startChild("Process Order", "Checkout Cart quantities");
		
		checkout(cart.getQuantities(), checkoutSpan);
		
		checkoutSpan.finish();
		return "success";
	}

	private void checkout(Map<String, Integer> quantities, ISpan span) {

		Map<String, Integer> tempInventory = dbHelper.getInventory(quantities.keySet(), span);

		ISpan inventorySpan = span.startChild("Reduce Inventory", "Reduce inventory from Cart quantities");
		for (String key : quantities.keySet()) {
			logger.info("Item " + key + " has quantity " + quantities.get(key));

			int currentInventory = tempInventory.get(key);
			currentInventory = currentInventory - quantities.get(key);
			if (currentInventory < 0) {
				String message = "No inventory for " + key;
				inventorySpan.setStatus(SpanStatus.fromHttpStatusCode(500, SpanStatus.INTERNAL_ERROR));
				inventorySpan.finish(); //resolve spans before throwing exception
				span.finish(); //resolve spans before throwing exception
				throw new RuntimeException(message);
			}

			tempInventory.put(key, currentInventory);

		}
		inventorySpan.finish();
	}

	/*
	 * BELOW ARE TEST FUNCTIONS
	 */
	@CrossOrigin
	@PostMapping("/postbody")
	public String postBody(@RequestBody String fullName) {
		return "Hello " + fullName;
	}
	
}