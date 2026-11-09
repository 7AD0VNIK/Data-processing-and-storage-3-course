package org.crawler;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Crawler {
    static final HttpClient client = HttpClient.newHttpClient();
    static final ObjectMapper mapper = new ObjectMapper();
    static final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    static final Set<String> seen = ConcurrentHashMap.newKeySet();
    static final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    static final AtomicInteger active = new AtomicInteger();
    static final CountDownLatch done = new CountDownLatch(1);
    static String base;

    static void crawl(String path) {
        if (!seen.add(path)) return;
        active.incrementAndGet();
        pool.submit(() -> {
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                var json = mapper.readTree(response.body());
                messages.add(json.get("message").asText());

                for (var successor : json.get("successors")) {
                    var next = successor.asText();
                    crawl(next.startsWith("/") ? next : "/" + next);
                }
            } catch (Exception e) { System.err.println("Ошибка " + path + ": " + e); }
            finally { if (active.decrementAndGet() == 0) done.countDown(); }
        });
    }

    public static void main(String[] args) throws Exception {
        base = "http://localhost:" + (args.length > 0 ? args[0] : "8080");
        crawl("/");
        done.await();
        pool.close();
        messages.sort(String::compareTo);
        messages.forEach(System.out::println);
        System.out.println("Собрано сообщений: " + messages.size());
    }
}
