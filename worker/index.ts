import { Container, getRandom } from '@cloudflare/containers';

export class PropelContainer extends Container {
  defaultPort = 8080;
  sleepAfter = '10m';
}

interface Env {
  PROPEL_CONTAINER: DurableObjectNamespace<PropelContainer>;
  ASSETS: Fetcher;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/')) {
      const container = await getRandom(env.PROPEL_CONTAINER, 2);
      return container.fetch(request);
    }
    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<Env>;
