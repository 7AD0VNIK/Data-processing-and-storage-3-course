package org.crawler;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class Crawler {
    static final HttpClient client = HttpClient.newHttpClient();
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
                var json = response.body();

                var message = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
                if (message.find()) messages.add(message.group(1));

                var successors = Pattern.compile("\"successors\"\\s*:\\s*\\[(.*?)\\]").matcher(json);
                if (successors.find()) {
                    var paths = Pattern.compile("\"([^\"]+)\"").matcher(successors.group(1));
                    while (paths.find()) crawl(paths.group(1).startsWith("/") ? paths.group(1) : "/" + paths.group(1));
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
