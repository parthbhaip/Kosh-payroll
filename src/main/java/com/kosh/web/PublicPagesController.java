package com.kosh.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The pages anyone can read without signing in. */
@Controller
class PublicPagesController {

	@GetMapping("/")
	String home() {
		return "public/home";
	}

	@GetMapping("/privacy")
	String privacy() {
		return "public/privacy";
	}

	@GetMapping("/terms")
	String terms() {
		return "public/terms";
	}

	@GetMapping("/help")
	String help() {
		return "public/help";
	}

}
