export default {
  async fetch(request) {
    return new Response('Hello from kwip (kino.watch image proxy)!', {
      headers: { 'content-type': 'text/plain; charset=utf-8' },
    });
  },
};
