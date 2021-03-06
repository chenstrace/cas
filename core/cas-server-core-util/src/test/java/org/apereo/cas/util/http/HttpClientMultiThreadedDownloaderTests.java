package org.apereo.cas.util.http;

import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.config.CasCoreUtilConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.File;

import static org.junit.Assert.*;

/**
 * This is {@link HttpClientMultiThreadedDownloaderTests}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = {
                AopAutoConfiguration.class,
                CasCoreUtilConfiguration.class})
@EnableScheduling
@Slf4j
public class HttpClientMultiThreadedDownloaderTests {
    @Autowired
    private ResourceLoader resourceLoader;

    @Test
    public void verify() throws Exception {
        final var resource = resourceLoader.getResource("https://raw.githubusercontent.com/apereo/cas/master/NOTICE");
        final var target = File.createTempFile("notice", ".md");
        final var downloader = new HttpClientMultiThreadedDownloader(resource, target);
        downloader.download();
        assertTrue(target.exists());
    }
}
