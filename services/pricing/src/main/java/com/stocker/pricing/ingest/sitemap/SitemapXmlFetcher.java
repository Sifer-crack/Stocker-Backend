package com.stocker.pricing.ingest.sitemap;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Fetches and parses sitemap.xml/sitemap-index.xml documents, extracting {@code <loc>} entries. */
class SitemapXmlFetcher {

	private static final Logger log = LoggerFactory.getLogger(SitemapXmlFetcher.class);

	private final WebClient webClient;

	SitemapXmlFetcher(WebClient webClient) {
		this.webClient = webClient;
	}

	/** All {@code <loc>} entries in the given sitemap/sitemap-index XML document. */
	List<String> fetchLocs(String sitemapUrl) {
		String body;
		try {
			body = webClient.get().uri(sitemapUrl).header("Accept", "application/xml")
					.retrieve().bodyToMono(String.class).block();
		} catch (Exception e) {
			log.warn("Failed to fetch sitemap {}: {}", sitemapUrl, e.getMessage());
			return List.of();
		}
		if (body == null || body.isBlank()) {
			return List.of();
		}
		return parseLocs(body);
	}

	private List<String> parseLocs(String xml) {
		List<String> locs = new ArrayList<>();
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// Prevent XXE: no DTDs, no external entities, no external DTD/schema access.
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
			NodeList locNodes = doc.getElementsByTagName("loc");
			for (int i = 0; i < locNodes.getLength(); i++) {
				Element element = (Element) locNodes.item(i);
				String text = element.getTextContent();
				if (text != null && !text.isBlank()) {
					locs.add(text.trim());
				}
			}
		} catch (Exception e) {
			log.warn("Failed to parse sitemap XML: {}", e.getMessage());
		}
		return locs;
	}
}
