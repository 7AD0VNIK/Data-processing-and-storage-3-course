package org.sync;

import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.*;

public class SyncListDemo {
    interface Store extends Iterable<String> {
        void addFirst(String text);
        void pass(long between, long inside, AtomicLong steps) throws InterruptedException;
    }

    static void pause(long ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }

    static class Node {
        final String text; // строки не должны меняться
        final ReentrantLock lock = new ReentrantLock(true);
        Node next;
        Node(String text) { this.text = text; }
    }

    static class LinkedStore implements Store {
        private final Node head = new Node(null); //dummy head
        private final ReadWriteLock gate = new ReentrantReadWriteLock(true);

        public void addFirst(String text) {
            head.lock.lock();
            try {
                gate.readLock().lock();
                try {
                    Node node = new Node(text);
                    node.next = head.next;
                    head.next = node;
                } finally { gate.readLock().unlock(); }
            } finally { head.lock.unlock(); }
        }

        public Iterator<String> iterator() {
            List<String> copy = new ArrayList<>();
            gate.writeLock().lock();
            try {
                for (Node n = head.next; n != null; n = n.next) copy.add(n.text);
            } finally { gate.writeLock().unlock(); }
            return copy.iterator();
        }

        // каждый шаг заново находит соседнюю пару. Во время задержек блокировки свободны.
        private boolean step(int index, AtomicLong steps) throws InterruptedException {
            Node prev = head, cur = null, next = null;
            boolean curLocked = false, nextLocked = false;
            prev.lock.lockInterruptibly();
            try {
                cur = prev.next;
                if (cur == null) return false;
                cur.lock.lockInterruptibly();
                curLocked = true;

                for (int i = 0; i < index; i++) {
                    Node following = cur.next;
                    if (following == null) return false;
                    following.lock.lockInterruptibly();
                    prev.lock.unlock();
                    prev = cur;
                    cur = following;
                }

                next = cur.next;
                if (next == null) return false;
                next.lock.lockInterruptibly();
                nextLocked = true;

                if (cur.text.compareTo(next.text) > 0) {
                    gate.readLock().lock();
                    try {
                        prev.next = next;
                        cur.next = next.next;
                        next.next = cur;
                    } finally { gate.readLock().unlock(); }
                }
                steps.incrementAndGet();
                return true;
            } finally {
                if (nextLocked) next.lock.unlock();
                if (curLocked) cur.lock.unlock();
                prev.lock.unlock();
            }
        }

        public void pass(long between, long inside, AtomicLong steps) throws InterruptedException {
            for (int i = 0; ; i++) {
                pause(inside); // задержка внутри шага, до захвата блокировок
                if (!step(i, steps)) return;
                pause(between); // задержка между шагами, тоже без блокировок
            }
        }
    }

    static class ArrayStore implements Store {
        private final List<String> list = Collections.synchronizedList(new ArrayList<>());

        public void addFirst(String text) { list.add(0, text); }

        public Iterator<String> iterator() {
            synchronized (list) { return new ArrayList<>(list).iterator(); }
        }

        public void pass(long between, long inside, AtomicLong steps) throws InterruptedException {
            for (int i = 0; ; i++) {
                synchronized (list) {
                    if (i + 1 >= list.size()) return;
                    pause(inside); // ArrayList удерживает общий монитор во время шага
                    String a = list.get(i), b = list.get(i + 1);
                    if (a.compareTo(b) > 0) {
                        list.set(i, b);
                        list.set(i + 1, a);
                    }
                    steps.incrementAndGet();
                }
                pause(between);
            }
        }
    }

    static Store create(String mode) {
        return switch (mode) {
            case "linked" -> new LinkedStore();
            case "array" -> new ArrayStore();
            default -> throw new IllegalArgumentException("Mode: linked or array");
        };
    }

    static Thread[] start(Store store, int count, long between, long inside, AtomicLong steps, CountDownLatch go) {
        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        long before = steps.get();
                        store.pass(between, inside, steps);
                        if (steps.get() == before) pause(10);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "sorter-" + i);
            threads[i].start();
        }
        return threads;
    }

    static void stop(Thread[] threads) throws InterruptedException {
        for (Thread t : threads) t.interrupt();
        for (Thread t : threads) t.join();
    }

    static void print(Store store) {
        System.out.println("--- list ---");
        for (String text : store) System.out.println(text);
        System.out.println("------------");
    }

    static void interactive(String mode, int workers, long between, long inside) throws Exception {
        Store store = create(mode);
        AtomicLong steps = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = start(store, workers, between, inside, steps, go);
        go.countDown();
        System.out.println("Enter strings; empty line = list; /stats; /quit");
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("/quit")) break;
                if (line.equals("/stats")) { System.out.println("Steps: " + steps.get()); continue; }
                if (line.isEmpty()) { print(store); continue; }
                for (int pos = 0; pos < line.length();) {
                    int len = Math.min(80, line.codePointCount(pos, line.length()));
                    int end = line.offsetByCodePoints(pos, len);
                    store.addFirst(line.substring(pos, end));
                    pos = end;
                }
            }
        } finally {
            stop(threads);
            System.out.println("Total steps: " + steps.get());
        }
    }

    static void benchmark(int workers, long between, long inside, int nodes, long duration) throws Exception {
        for (String mode : new String[]{"linked", "array"}) {
            Store store = create(mode);
            Random random = new Random(42);
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < nodes; i++) {
                String value = String.format("%08d", random.nextInt(1_000_000));
                expected.add(value);
                store.addFirst(value);
            }
            AtomicLong steps = new AtomicLong();
            CountDownLatch go = new CountDownLatch(1);
            Thread[] threads = start(store, workers, between, inside, steps, go);
            long begin = System.nanoTime();
            go.countDown();
            try { Thread.sleep(duration); } finally { stop(threads); }
            double seconds = (System.nanoTime() - begin) / 1e9;
            List<String> actual = new ArrayList<>();
            for (String text : store) actual.add(text);
            Collections.sort(expected);
            Collections.sort(actual);
            if (!expected.equals(actual)) throw new AssertionError("Lost or duplicated strings");
            System.out.printf(Locale.ROOT, "%s: steps=%d, time=%.3fs, steps/s=%.2f, data=OK%n",
                    mode, steps.get(), seconds, steps.get() / seconds);
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "linked";
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        long between = args.length > 2 ? Long.parseLong(args[2]) : 1000;
        long inside = args.length > 3 ? Long.parseLong(args[3]) : 1000;
        if (workers < 1 || between < 0 || inside < 0) throw new IllegalArgumentException("Bad threads/delays");
        if (mode.equals("bench")) {
            int nodes = args.length > 4 ? Integer.parseInt(args[4]) : 30;
            long duration = args.length > 5 ? Long.parseLong(args[5]) : 10000;
            if (nodes < 2 || duration <= 0) throw new IllegalArgumentException("Bad nodes/duration");
            benchmark(workers, between, inside, nodes, duration);
        } else interactive(mode, workers, between, inside);
    }
}
