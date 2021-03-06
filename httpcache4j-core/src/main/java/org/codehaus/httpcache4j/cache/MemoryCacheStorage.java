/*
 * Copyright (c) 2008, The Codehaus. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package org.codehaus.httpcache4j.cache;

import org.codehaus.httpcache4j.HTTPRequest;
import org.codehaus.httpcache4j.HTTPResponse;
import org.codehaus.httpcache4j.payload.Payload;
import org.codehaus.httpcache4j.payload.ByteArrayPayload;
import org.codehaus.httpcache4j.util.IOUtils;
import org.codehaus.httpcache4j.util.MemoryCache;
import org.codehaus.httpcache4j.util.LRUMap;

import java.net.URI;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;
import java.io.InputStream;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * In Memory implementation of a cache storage.
 *
 * @author <a href="mailto:hamnis@codehaus.org">Erlend Hamnaberg</a>
 */
public class MemoryCacheStorage implements CacheStorage {

    protected final int capacity;
    protected MemoryCache cache;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock read = lock.readLock();
    private final Lock write = lock.writeLock();
    private int varyCapacity;

    public MemoryCacheStorage() {
        this(1000, 10);
    }

    protected MemoryCacheStorage(int capacity, int varyCapacity) {
        this.capacity = capacity;
        this.cache = new MemoryCache(this.capacity);
        this.varyCapacity = varyCapacity;
    }

    private HTTPResponse rewriteResponse(Key key, HTTPResponse response) {
        if (response.hasPayload()) {
            Payload payload = response.getPayload().get();
            try(InputStream stream = payload.getInputStream()) {
                return response.withPayload(createPayload(key, payload, stream));
            } catch (IOException ignore) {
            }
        }
        else {
            return response;
        }
        throw new IllegalArgumentException("Unable to cache response");
    }

    public final HTTPResponse insert(final HTTPRequest request, final HTTPResponse response) {
        Key key = Key.create(request, response);
        HTTPResponse cacheableResponse = rewriteResponse(key, response);
        return withWriteLock(() -> {
            invalidate(key);
            return putImpl(key, cacheableResponse);
        });
    }

    protected HTTPResponse putImpl(final Key pKey, final HTTPResponse pCacheableResponse) {
        CacheItem item = createCacheItem(pCacheableResponse);
        LRUMap<Vary, CacheItem> varyCacheItemMap = cache.get(pKey.getURI());
        if (varyCacheItemMap == null) {
            varyCacheItemMap = new LRUMap<>(varyCapacity);
            cache.put(pKey.getURI(), varyCacheItemMap);
        }
        varyCacheItemMap.put(pKey.getVary(), item);
        return pCacheableResponse;
    }

    protected CacheItem createCacheItem(HTTPResponse pCacheableResponse) {
        return new DefaultCacheItem(pCacheableResponse);
    }

    public final HTTPResponse update(final HTTPRequest request, final HTTPResponse response) {
        return withWriteLock(() -> {
            Key key = Key.create(request, response);
            return putImpl(key, response);
        });
    }

    protected Payload createPayload(Key key, Payload payload, InputStream stream) throws IOException {
        ByteArrayPayload p = new ByteArrayPayload(stream, payload.getMimeType());
        if (p.isAvailable()) {
            return p;
        }
        return null;
    }

    public final CacheItem get(HTTPRequest request) {
        return withReadLock(() -> {
            Map<Vary, CacheItem> varyCacheItemMap = cache.get(request.getNormalizedURI());
            if (varyCacheItemMap == null) {
                return null;
            }
            else {
                for (Map.Entry<Vary, CacheItem> entry : varyCacheItemMap.entrySet()) {
                    if (entry.getKey().matches(request)) {
                        return entry.getValue();
                    }
                }
            }
            return null;
        });
    }

    public final void invalidate(URI uri) {
        withVoidWriteLock(() -> {
            Map<Vary, CacheItem> varyCacheItemMap = cache.get(uri);
            if (varyCacheItemMap != null) {
                Set<Vary> vary = new HashSet<>(varyCacheItemMap.keySet());
                for (Vary v : vary) {
                    Key key = new Key(uri, v);
                    cache.remove(key);
                }
            }
        });
    }

    public final CacheItem get(Key key) {
        return withReadLock(() -> {
            Map<Vary, CacheItem> varyCacheItemMap = cache.get(key.getURI());
            if (varyCacheItemMap != null) {
                return varyCacheItemMap.get(key.getVary());
            }
            return null;
        });
    }

    private void invalidate(Key key) {
        cache.remove(key);
    }

    public final void clear() {
        withVoidWriteLock(() -> {
            Set<URI> uris = new HashSet<URI>(cache.keySet());
            for (URI uri : uris) {
                cache.remove(uri);
            }
            afterClear();
        });
    }


    protected void afterClear() {
    }

    public final int size() {
        return withReadLock(() -> {
            int size = 0;
            for (Map<Vary, CacheItem> map : cache.values()) {
                size += map.size();
            }
            return size;
        });
    }

    public final Iterator<Key> iterator() {
        return withReadLock(() -> {
            HashSet<Key> keys = new HashSet<Key>();
            for (Map.Entry<URI, LRUMap<Vary, CacheItem>> entry : cache.entrySet()) {
                for (Vary vary : entry.getValue().keySet()) {
                    keys.add(new Key(entry.getKey(), vary));
                }
            }
            return Collections.unmodifiableSet(keys).iterator();
        });
    }

    protected final <A> A withReadLock(Supplier<A> block) {
        read.lock();
        try {
            return block.get();
        } finally {
            read.unlock();
        }
    }

    protected final <A> A withWriteLock(Supplier<A> block) {
        write.lock();
        try {
            return block.get();
        } finally {
            write.unlock();
        }
    }
    protected final void withVoidWriteLock(Runnable block) {
        write.lock();
        try {
            block.run();
        } finally {
            write.unlock();
        }
    }

    @Override
    public void shutdown() {
    }
}
