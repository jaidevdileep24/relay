package com.dileep.relay.service;

import com.dileep.relay.api.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test. Every case uses an IP literal rather than a hostname, so the
 * suite never depends on DNS or on the machine having a network.
 */
class SsrfGuardTest {

	private final SsrfGuard guard = new SsrfGuard();

	@ParameterizedTest
	@ValueSource(strings = {
			"https://127.0.0.1/hook",          // loopback
			"https://127.1.2.3/hook",          // the rest of 127/8
			"https://[::1]/hook",              // IPv6 loopback
			"https://0.0.0.0/hook",            // any-local
			"https://10.1.2.3/hook",           // private class A
			"https://172.16.5.4/hook",         // private class B
			"https://192.168.1.1/hook",        // private class C
			"https://169.254.1.1/hook",        // link-local
			"https://224.0.0.1/hook"           // multicast
	})
	@DisplayName("rejects every address range that lives inside our own network")
	void rejectsInternalAddresses(String url) {
		assertThatThrownBy(() -> guard.validate(url))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	@DisplayName("rejects the cloud metadata endpoint - the highest-value SSRF target there is")
	void rejectsCloudMetadataEndpoint() {
		// 169.254.169.254 serves IAM credentials to anything inside the VPC
		// that asks. It is link-local, which is what catches it.
		assertThatThrownBy(() -> guard.validate("https://169.254.169.254/latest/meta-data/iam/security-credentials/"))
				.isInstanceOf(ValidationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://93.184.216.34/hook",       // plain http
			"ftp://93.184.216.34/hook",        // wrong scheme entirely
			"HTTP://93.184.216.34/hook"        // and case-insensitively
	})
	@DisplayName("rejects anything that is not https")
	void rejectsNonHttpsSchemes(String url) {
		assertThatThrownBy(() -> guard.validate(url))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	@DisplayName("rejects a malformed url")
	void rejectsMalformedUrl() {
		assertThatThrownBy(() -> guard.validate("https://exa mple.com/hook"))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	@DisplayName("rejects a url with no host")
	void rejectsUrlWithoutHost() {
		assertThatThrownBy(() -> guard.validate("https:///hook"))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	@DisplayName("rejects a host that does not resolve")
	void rejectsUnresolvableHost() {
		assertThatThrownBy(() -> guard.validate("https://this-host-does-not-exist.invalid/hook"))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	@DisplayName("allows a public https address")
	void allowsPublicHttpsAddress() {
		assertThatCode(() -> guard.validate("https://93.184.216.34/webhooks"))
				.doesNotThrowAnyException();
	}
}
