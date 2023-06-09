package com.example.demo.service;

import com.example.demo.dao.URLRepository;
import com.example.demo.model.URL;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.swing.text.html.Option;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Log4j2
@Service
public class URLService {

    @Value("#{${com.urlfeederservice.topics}}")
    Map<String,String> kafkaTopics;

    @Value("#{${com.urlfeederservice.cooldown}}")
    private Integer coolDown;
    @Autowired
    private URLRepository urlRepository;

    @Autowired
    private KafkaService kafkaService;
    @Autowired
    private CacheService cacheService;

    @Async
    public void save(Set<URL> urls){
        for(URL url:urls) {
            try {
                log.info("------- {} " + Thread.currentThread().getName());
                if(cacheService.get(url.getUrl())!=null){
                    return ;
                }
                Optional<URL> existingURLOpt = urlRepository.findByUrl(url.getUrl());
                Optional<String> optContentType = Optional.empty();
                if (!existingURLOpt.isEmpty()) {
                    URL existingURL = existingURLOpt.get();
                    // we are going to allow processing if the URL has been processed more than 7 days ago
                    if (existingURL.getLastProcessed().getTime() + TimeUnit.DAYS.toMillis(coolDown) > System.currentTimeMillis()) {
                        log.info("URL {} already processed on {}"+ existingURL.getUrl(), existingURL.getLastProcessed());
                        cacheService.set(existingURL);
                        return;
                    }
                    url = existingURL;
                    optContentType = Optional.of(existingURL.getContentType());
                }

                url.setLastProcessed(new Timestamp(System.currentTimeMillis()));
                url.setTimesProcessed(1);
                log.info("here after query empty!!!");
                optContentType = getContentType(url.getUrl());
                if (optContentType.isEmpty()) {
                    log.warn("Content type not found for URL: {}" + url.getUrl());
                    return;
                }
                Optional<String> optTopic = getTopicByContentType(optContentType.get());
                log.info("here");
                if (optTopic.isEmpty()) {
                    log.warn("Content type {} not mapped" + optContentType.get());
                    return;
                }
                String topic = optTopic.get();
                if (url.getContentType() == null || url.getContentType().isEmpty()) {
                    url.setContentType(optContentType.get());
                }
                log.info("URL: {} sending to topic :{} " + url.getUrl() + topic);
                kafkaService.send(topic, url); // comment for testing
                //save in cache
                cacheService.set(url);
                urlRepository.save(url);
            } catch (Exception e) {
                log.error("Exception: " + e);
            }
        }
    }

    private Optional<String> getContentType(String path) throws IOException{

        java.net.URL url = new java.net.URL(path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        log.info("getContentType:   "+  path);
        connection.setRequestMethod("HEAD");
        connection.connect();
        return Optional.of(connection.getContentType());
    }
    private Optional<String> getTopicByContentType(String rawContentType){
        log.info("rawContentType: " + rawContentType);
        String contentType = rawContentType.split(";")[0];
        log.info("Key: "+contentType);
        if(kafkaTopics.containsKey(contentType)){
            return Optional.of(kafkaTopics.get(contentType));
        }
        return Optional.empty();
    }
    private Optional<String> getTopicByPath(String path) throws IOException{
        java.net.URL url = new java.net.URL(path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("HEAD");
        connection.connect();
        String rawContentType = connection.getContentType();
        log.info("URL: "+path + rawContentType);
        String contentType = rawContentType.split(";")[0];
        log.info("Key: {}"+ contentType);
        if(kafkaTopics.containsKey(contentType)){
            return Optional.of(kafkaTopics.get(contentType));
        }
        return Optional.empty();
    }
}
