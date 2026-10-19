package com.abcconsulting.slme.server.stores;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IconPageParserTests {

	@Test
	void ordersDeclaredIconsBestFirstAndSkipsSvg() {
		String html = """
				<html><head>
				<link rel="icon" href="/favicon.ico">
				<LINK REL='shortcut icon' HREF='/small.png' SIZES='16x16'>
				<link rel="icon" type="image/png" href="https://cdn.example.com/icons/icon-196.png?v=2&amp;x=1" sizes="196x196"/>
				<link rel="apple-touch-icon" href="touch.png">
				<link rel="icon" type="image/svg+xml" href="/logo.svg">
				<link rel="mask-icon" href="/mask.svg" color="#000">
				<link rel="stylesheet" href="/site.css">
				<link href="//static.example.com/precomposed.png" rel="apple-touch-icon-precomposed" sizes="152x152">
				</head></html>
				""";
		List<IconPageParser.Candidate> candidates = IconPageParser.parse(html, URI.create("https://www.example.com/stores/"));
		assertEquals(List.of(
				"https://cdn.example.com/icons/icon-196.png?v=2&x=1",
				"https://www.example.com/stores/touch.png",
				"https://static.example.com/precomposed.png",
				"https://www.example.com/favicon.ico",
				"https://www.example.com/small.png"),
				candidates.stream().map(candidate -> candidate.url().toString()).toList());
		assertEquals(List.of(196, 180, 152, 32, 16), candidates.stream().map(candidate -> candidate.size()).toList());
	}

	@Test
	void findsNothingInPagesWithoutIcons() {
		assertEquals(List.of(), IconPageParser.parse("<html><body>Access denied</body></html>", URI.create("https://a.example/")));
	}
}
