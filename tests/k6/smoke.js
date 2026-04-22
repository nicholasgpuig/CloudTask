import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// Smoke test: minimal load to verify system works
export const options = {
  vus: 1,
  duration: '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],    // <1% errors
    http_req_duration: ['p(95)<2000'], // 95% of requests under 2s
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Setup: register a test user and get token
export function setup() {
  const email = `loadtest-${uuidv4()}@test.com`;
  const password = 'testpassword123';

  const registerRes = http.post(
    `${BASE_URL}/auth/register`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(registerRes, {
    'register succeeded': (r) => r.status === 201,
  });

  if (registerRes.status !== 201) {
    console.error(`Registration failed: ${registerRes.status} ${registerRes.body}`);
    return null;
  }

  const authData = JSON.parse(registerRes.body);
  return { token: authData.accessToken, email };
}

// Main test scenario
export default function (data) {
  if (!data || !data.token) {
    console.error('No auth token available');
    return;
  }

  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`,
    'Idempotency-Key': uuidv4(),
  };

  // Create a job
  const createRes = http.post(
    `${BASE_URL}/jobs`,
    JSON.stringify({
      type: 'sleep',
      payload: { seconds: 1 },
    }),
    { headers }
  );

  const jobCreated = check(createRes, {
    'job created': (r) => r.status === 201,
    'job has id': (r) => {
      try {
        return JSON.parse(r.body).id !== undefined;
      } catch {
        return false;
      }
    },
  });

  if (!jobCreated) {
    console.error(`Job creation failed: ${createRes.status} ${createRes.body}`);
    return;
  }

  const job = JSON.parse(createRes.body);

  // Get job status
  const getRes = http.get(`${BASE_URL}/jobs/${job.id}`, { headers });

  check(getRes, {
    'get job succeeded': (r) => r.status === 200,
    'job status valid': (r) => {
      try {
        const status = JSON.parse(r.body).status;
        return ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED'].includes(status);
      } catch {
        return false;
      }
    },
  });

  // List jobs
  const listRes = http.get(`${BASE_URL}/jobs`, { headers });

  check(listRes, {
    'list jobs succeeded': (r) => r.status === 200,
    'list returns array': (r) => {
      try {
        return Array.isArray(JSON.parse(r.body));
      } catch {
        return false;
      }
    },
  });

  sleep(1);
}
