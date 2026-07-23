import { env } from 'cloudflare:workers';
import { Container, getRandom } from '@cloudflare/containers';

interface ContainerSecrets {
  PROPEL_FEISHU_ACCESS_TOKEN?: string;
  PROPEL_FEISHU_APP_ID?: string;
  PROPEL_FEISHU_APP_SECRET?: string;
}

const containerSecrets = env as unknown as ContainerSecrets;

export class PropelContainer extends Container {
  defaultPort = 8080;
  sleepAfter = '10m';
  envVars = {
    PROPEL_FEISHU_ACCESS_TOKEN: containerSecrets.PROPEL_FEISHU_ACCESS_TOKEN ?? '',
    PROPEL_FEISHU_APP_ID: containerSecrets.PROPEL_FEISHU_APP_ID ?? '',
    PROPEL_FEISHU_APP_SECRET: containerSecrets.PROPEL_FEISHU_APP_SECRET ?? '',
  };

  override onError(error: unknown) {
    console.error('Propel Java container error:', error);
  }
}

interface Env {
  PROPEL_CONTAINER: DurableObjectNamespace<PropelContainer>;
  ASSETS: Fetcher;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/')) {
      try {
        const container = await getRandom(env.PROPEL_CONTAINER, 2);
        return await container.fetch(request);
      } catch (error) {
        console.error('Propel container request failed:', error);
        return Response.json(
          {
            status: 'starting',
            error: 'The Java export container is starting or temporarily unavailable. Retry shortly.',
          },
          {
            status: 503,
            headers: { 'Cache-Control': 'no-store', 'Retry-After': '10' },
          },
        );
      }
    }
    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<Env>;
