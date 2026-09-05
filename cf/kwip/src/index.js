const DEFAULT_CACHE_TTL_SECONDS = 30 * 24 * 60 * 60; // 30 days
const DEFAULT_MAX_IMAGE_BYTES = 10 * 1024 * 1024; // 10 MiB
const MAX_REDIRECTS = 3;

const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308]);

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
  }
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await handleRequest(request, env, ctx);
    } catch (error) {
      if (error instanceof HttpError) {
        return errorResponse(error.status, error.message);
      }

      console.error('Unhandled image proxy error', error);
      return errorResponse(502, 'Bad Gateway');
    }
  },
};

async function handleRequest(request, env, ctx) {
  const method = request.method.toUpperCase();

  if (method !== 'GET' && method !== 'HEAD') {
    return new Response('Method Not Allowed', {
      status: 405,
      headers: {
        Allow: 'GET, HEAD',
        'Cache-Control': 'no-store',
      },
    });
  }

  const requestUrl = new URL(request.url);

  if (requestUrl.pathname !== '/img') {
    return new Response('Not Found', {
      status: 404,
      headers: {
        'Cache-Control': 'no-store',
      },
    });
  }

  const rawOriginUrl = requestUrl.searchParams.get('url');
  if (!rawOriginUrl) {
    throw new HttpError(400, 'Missing "url" query parameter');
  }

  const originUrl = parseOriginUrl(rawOriginUrl);

  // Fragment is never sent to HTTP origin and should not create
  // a different cache entry.
  originUrl.hash = '';

  const allowedHosts = await loadAllowedHosts(env);

  validateOriginUrl(originUrl, allowedHosts, {
    status: 403,
    message: 'Origin is not allowed',
  });

  const cacheTtlSeconds = positiveInteger(
    env.IMAGE_CACHE_TTL_SECONDS,
    DEFAULT_CACHE_TTL_SECONDS,
  );

  const maxImageBytes = positiveInteger(
    env.IMAGE_MAX_BYTES,
    DEFAULT_MAX_IMAGE_BYTES,
  );

  const cacheKeys = buildCacheKeys(requestUrl, originUrl, request);
  const cache = caches.default;

  const cachedResponse = await cache.match(cacheKeys.lookup);

  if (cachedResponse) {
    return withCacheStatus(
      cachedResponse,
      'HIT',
      method === 'HEAD',
    );
  }

  const originResponse = await fetchOrigin(
    originUrl,
    method,
    allowedHosts,
  );

  // Redirects have already been handled manually by fetchOrigin().
  //
  // 404/5xx and any other non-2xx response are not cached.
  // Preserve the origin status as required by the contract.
  if (!originResponse.ok) {
    await discardBody(originResponse);

    return upstreamStatusResponse(originResponse.status);
  }

  // Cache API cannot cache 206 responses and this proxy deliberately
  // does not implement byte-range semantics.
  if (originResponse.status === 206) {
    await discardBody(originResponse);
    throw new HttpError(502, 'Partial origin responses are not supported');
  }

  if (!isImageResponse(originResponse)) {
    await discardBody(originResponse);
    throw new HttpError(502, 'Origin returned a non-image response');
  }

  const declaredSize = getContentLength(originResponse);

  if (declaredSize !== null && declaredSize > maxImageBytes) {
    await discardBody(originResponse);
    throw new HttpError(502, 'Origin image is too large');
  }

  // HEAD can reuse an existing GET cache entry, but on a miss there
  // is no point downloading the full image just to populate the cache.
  if (method === 'HEAD') {
    const response = buildClientResponse(
      originResponse,
      null,
      cacheTtlSeconds,
      false,
    );

    return withCacheStatus(response, 'MISS', true);
  }

  // We deliberately read at most MAX_IMAGE_BYTES.
  //
  // Content-Length cannot be trusted by itself:
  // - it may be absent;
  // - it may be incorrect.
  const body = await readBodyWithLimit(
    originResponse,
    maxImageBytes,
  );

  const response = buildClientResponse(
    originResponse,
    body,
    cacheTtlSeconds,
    true,
  );

  // cache.put() only accepts GET request keys.
  //
  // Do not delay the client response waiting for the cache write.
  ctx.waitUntil(
    cache.put(cacheKeys.store, response.clone()).catch((error) => {
      console.error('Image cache put failed', {
        url: originUrl.toString(),
        error,
      });
    }),
  );

  return withCacheStatus(response, 'MISS');
}

function parseOriginUrl(value) {
  let url;

  try {
    url = new URL(value);
  } catch {
    throw new HttpError(400, 'Invalid absolute URL');
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new HttpError(400, 'Only HTTP(S) origins are supported');
  }

  return url;
}

/**
 * Resolve configuration.
 *
 * Baseline configuration:
 *
 *   env.IMAGE_ALLOWED_HOSTS
 *
 * can be either:
 *
 *   ["image.tmdb.org", "cdn.example.com"]
 *
 * or:
 *
 *   image.tmdb.org,cdn.example.com
 *
 *
 * Optional dynamic configuration:
 *
 *   env.IMAGE_PROXY_CONFIG
 *
 * KV key:
 *
 *   allowed_hosts
 *
 * JSON value:
 *
 *   ["image.tmdb.org", "*.kino.watch"]
 *
 *
 * If the KV key exists, it is authoritative and replaces
 * IMAGE_ALLOWED_HOSTS.
 */
async function loadAllowedHosts(env) {
  let raw = env.IMAGE_ALLOWED_HOSTS;

  if (env.IMAGE_PROXY_CONFIG) {
    let dynamicHosts;

    try {
      dynamicHosts = await env.IMAGE_PROXY_CONFIG.get(
        'allowed_hosts',
        {
          type: 'json',

          // Cloudflare KV edge cache for the configuration itself.
          // Changes therefore become visible shortly rather than
          // requiring a Worker deployment.
          cacheTtl: 60,
        },
      );
    } catch (error) {
      console.error('Failed to read image proxy configuration', error);

      // Fail closed. If dynamic security configuration cannot
      // be read, don't silently fall back to a potentially stale
      // allow-list.
      throw new HttpError(
        503,
        'Image proxy configuration is unavailable',
      );
    }

    if (dynamicHosts !== null) {
      raw = dynamicHosts;
    }
  }

  const hosts = normalizeAllowedHosts(raw);

  if (hosts.length === 0) {
    throw new HttpError(
      503,
      'No image origins are configured',
    );
  }

  return hosts;
}

function normalizeAllowedHosts(value) {
  let values;

  if (Array.isArray(value)) {
    values = value;
  } else if (typeof value === 'string') {
    const trimmed = value.trim();

    if (!trimmed) {
      return [];
    }

    if (trimmed.startsWith('[')) {
      try {
        const parsed = JSON.parse(trimmed);

        if (!Array.isArray(parsed)) {
          throw new Error();
        }

        values = parsed;
      } catch {
        throw new HttpError(
          503,
          'IMAGE_ALLOWED_HOSTS contains invalid JSON',
        );
      }
    } else {
      values = trimmed.split(',');
    }
  } else if (value == null) {
    return [];
  } else {
    throw new HttpError(
      503,
      'IMAGE_ALLOWED_HOSTS has invalid format',
    );
  }

  const result = [];

  for (const value of values) {
    if (typeof value !== 'string') {
      throw new HttpError(
        503,
        'Allowed host must be a string',
      );
    }

    let host = value.trim().toLowerCase();

    if (!host) {
      continue;
    }

    // Config contains hostnames, not URLs.
    if (
      host.includes('://') ||
      host.includes('/') ||
      host.includes('?') ||
      host.includes('#') ||
      host.includes(':')
    ) {
      throw new HttpError(
        503,
        `Invalid allowed host: ${value}`,
      );
    }

    // Avoid ambiguous DNS form.
    host = host.replace(/\.$/, '');

    if (host === '*' || host === '*.') {
      throw new HttpError(
        503,
        'Global wildcard is not allowed',
      );
    }

    if (host.startsWith('*.')) {
      const suffix = host.slice(2);

      if (!isValidHostname(suffix)) {
        throw new HttpError(
          503,
          `Invalid wildcard hostname: ${value}`,
        );
      }

      result.push(`*.${suffix}`);
      continue;
    }

    if (!isValidHostname(host)) {
      throw new HttpError(
        503,
        `Invalid allowed hostname: ${value}`,
      );
    }

    result.push(host);
  }

  return [...new Set(result)];
}

function validateOriginUrl(
  url,
  allowedHosts,
  {
    status,
    message,
  },
) {
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new HttpError(status, message);
  }

  // Do not allow credentials in proxied URLs.
  if (url.username || url.password) {
    throw new HttpError(status, message);
  }

  // URL.port is empty for default :80 / :443.
  // Block arbitrary services running on custom ports.
  if (url.port) {
    throw new HttpError(status, message);
  }

  if (!isAllowedHostname(url.hostname, allowedHosts)) {
    throw new HttpError(status, message);
  }
}

function isAllowedHostname(hostname, rules) {
  const host = hostname.toLowerCase().replace(/\.$/, '');

  for (const rule of rules) {
    if (rule.startsWith('*.')) {
      const suffix = rule.slice(2);

      // *.example.com matches:
      //
      //   a.example.com
      //   foo.bar.example.com
      //
      // but deliberately does NOT match:
      //
      //   example.com
      //
      // Add example.com explicitly if both are needed.
      if (
        host.length > suffix.length &&
        host.endsWith(`.${suffix}`)
      ) {
        return true;
      }

      continue;
    }

    if (host === rule) {
      return true;
    }
  }

  return false;
}

function isValidHostname(host) {
  if (!host || host.length > 253) {
    return false;
  }

  const labels = host.split('.');

  return labels.every((label) => {
    if (
      !label ||
      label.length > 63 ||
      label.startsWith('-') ||
      label.endsWith('-')
    ) {
      return false;
    }

    return /^[a-z0-9-]+$/i.test(label);
  });
}

/**
 * The cache key is always:
 *
 *   https://worker.example/img?url=<canonical-origin-url>
 *
 * Any unrelated query params are deliberately ignored.
 *
 * This guarantees one origin URL => one cache key.
 */
function buildCacheKeys(requestUrl, originUrl, request) {
  const cacheUrl = new URL(requestUrl);

  cacheUrl.hash = '';
  cacheUrl.search = '';
  cacheUrl.searchParams.set('url', originUrl.toString());

  // cache.match() understands conditional headers if cached
  // response has ETag / Last-Modified.
  const lookupHeaders = new Headers();

  const ifNoneMatch = request.headers.get('If-None-Match');
  const ifModifiedSince = request.headers.get('If-Modified-Since');

  if (ifNoneMatch) {
    lookupHeaders.set('If-None-Match', ifNoneMatch);
  }

  if (ifModifiedSince) {
    lookupHeaders.set(
      'If-Modified-Since',
      ifModifiedSince,
    );
  }

  return {
    lookup: new Request(cacheUrl.toString(), {
      method: 'GET',
      headers: lookupHeaders,
    }),

    // cache.put() requires a GET request.
    store: new Request(cacheUrl.toString(), {
      method: 'GET',
    }),
  };
}

/**
 * Fetch origin with redirect: manual.
 *
 * Redirects are followed only when every destination passes the
 * exact same allow-list policy.
 */
async function fetchOrigin(
  initialUrl,
  method,
  allowedHosts,
) {
  let currentUrl = new URL(initialUrl);
  const visited = new Set();

  for (let redirects = 0; ; redirects++) {
    const currentUrlString = currentUrl.toString();

    if (visited.has(currentUrlString)) {
      throw new HttpError(
        502,
        'Origin redirect loop detected',
      );
    }

    visited.add(currentUrlString);

    let response;

    try {
      response = await fetch(currentUrlString, {
        method,

        // Important: don't forward Cookie, Authorization,
        // Range or client conditional headers.
        //
        // Conditional origin requests are especially undesirable
        // here: on an edge-cache MISS we need the actual bytes,
        // not a 304.
        headers: {
          // Keep the representation deterministic because Accept
          // is not part of the cache key.
          Accept: 'image/*,*/*;q=0.8',
        },

        redirect: 'manual',
      });
    } catch (error) {
      console.error('Origin fetch failed', {
        url: currentUrlString,
        error,
      });

      throw new HttpError(
        502,
        'Failed to fetch origin',
      );
    }

    if (!REDIRECT_STATUSES.has(response.status)) {
      return response;
    }

    const location = response.headers.get('Location');

    // We will not consume redirect response bodies.
    await discardBody(response);

    if (!location) {
      throw new HttpError(
        502,
        'Origin redirect has no Location header',
      );
    }

    if (redirects >= MAX_REDIRECTS) {
      throw new HttpError(
        502,
        'Too many origin redirects',
      );
    }

    let nextUrl;

    try {
      nextUrl = new URL(location, currentUrl);
    } catch {
      throw new HttpError(
        502,
        'Origin returned an invalid redirect URL',
      );
    }

    nextUrl.hash = '';

    validateOriginUrl(nextUrl, allowedHosts, {
      status: 502,
      message: 'Origin redirected to a forbidden host',
    });

    // Prevent HTTPS -> HTTP downgrade through an otherwise
    // allow-listed hostname.
    if (
      currentUrl.protocol === 'https:' &&
      nextUrl.protocol === 'http:'
    ) {
      throw new HttpError(
        502,
        'HTTPS downgrade redirect is not allowed',
      );
    }

    currentUrl = nextUrl;
  }
}

function isImageResponse(response) {
  const contentType =
    response.headers.get('Content-Type') || '';

  return contentType
    .trim()
    .toLowerCase()
    .startsWith('image/');
}

function getContentLength(response) {
  const raw = response.headers.get('Content-Length');

  if (!raw) {
    return null;
  }

  const value = raw.trim();

  if (!/^\d+$/.test(value)) {
    return null;
  }

  const size = Number(value);

  if (!Number.isFinite(size) || size < 0) {
    return null;
  }

  return size;
}

/**
 * Read the body without ever accepting more than maxBytes.
 *
 * We cannot stream directly to the client if we want the strict:
 *
 *   actual bytes > limit => HTTP 502
 *
 * guarantee, because after sending HTTP headers/status it is too
 * late to turn the response into a 502.
 */
async function readBodyWithLimit(response, maxBytes) {
  if (!response.body) {
    return new Uint8Array(0);
  }

  const reader = response.body.getReader();

  const chunks = [];
  let totalBytes = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) {
        break;
      }

      totalBytes += value.byteLength;

      if (totalBytes > maxBytes) {
        try {
          await reader.cancel('Image exceeds size limit');
        } catch {
          // Ignore cancellation failures.
        }

        throw new HttpError(
          502,
          'Origin image is too large',
        );
      }

      chunks.push(value);
    }
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // Ignore.
    }
  }

  const body = new Uint8Array(totalBytes);

  let offset = 0;

  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }

  return body;
}

function buildClientResponse(
  originResponse,
  body,
  cacheTtlSeconds,
  buffered,
) {
  const headers = new Headers(originResponse.headers);

  const cacheControl =
    `public, max-age=${cacheTtlSeconds}, immutable`;

  // Browser / application cache.
  headers.set(
    'Cache-Control',
    cacheControl,
  );

  // Retain the same approach used by the existing proxy.
  headers.set(
    'CDN-Cache-Control',
    cacheControl,
  );

  headers.set(
    'Cloudflare-CDN-Cache-Control',
    cacheControl,
  );

  headers.set(
    'Access-Control-Allow-Origin',
    '*',
  );

  headers.set(
    'Access-Control-Expose-Headers',
    'X-Image-Cache, ETag, Last-Modified',
  );

  headers.set(
    'X-Content-Type-Options',
    'nosniff',
  );

  // A response containing Set-Cookie cannot be stored normally
  // by Cloudflare Cache API.
  headers.delete('Set-Cookie');

  // We intentionally use exactly one cached representation per URL.
  // Client Accept is not forwarded to origin, so origin Vary does
  // not need to affect the proxy cache.
  headers.delete('Vary');

  if (buffered) {
    // Body has been reconstructed, so don't copy the origin's
    // potentially stale length.
    headers.delete('Content-Length');
  }

  // ETag and Last-Modified remain untouched if origin supplied them.

  return new Response(body, {
    status: originResponse.status,
    statusText: originResponse.statusText,
    headers,
  });
}

function withCacheStatus(
  response,
  cacheStatus,
  headOnly = false,
) {
  const headers = new Headers(response.headers);

  // CF-Cache-Status may describe Cloudflare's outer caching
  // machinery rather than our explicit caches.default lookup.
  // This header is deterministic for this Worker.
  headers.set('X-Image-Cache', cacheStatus);

  return new Response(
    headOnly ? null : response.body,
    {
      status: response.status,
      statusText: response.statusText,
      headers,
    },
  );
}

function upstreamStatusResponse(status) {
  return new Response(null, {
    status,
    headers: {
      'Cache-Control': 'no-store',
      'X-Image-Cache': 'MISS',
    },
  });
}

function errorResponse(status, message) {
  return new Response(message, {
    status,
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      'Cache-Control': 'no-store',
    },
  });
}

async function discardBody(response) {
  if (!response.body) {
    return;
  }

  try {
    await response.body.cancel();
  } catch {
    // Nothing to do.
  }
}

function positiveInteger(value, fallback) {
  if (value == null || value === '') {
    return fallback;
  }

  const parsed = Number(value);

  if (
    !Number.isSafeInteger(parsed) ||
    parsed <= 0
  ) {
    return fallback;
  }

  return parsed;
}
