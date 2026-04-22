import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const jobsCreated = new Counter('jobs_created');
const jobCreationErrors = new Counter('job_creation_errors');
const jobCreationRate = new Rate('job_creation_success_rate');
const jobCreationDuration = new Trend('job_creation_duration');

// Target: 300 requests per second
const TARGET_RPS = parseInt(__ENV.RPS) || 300;
const DURATION = __ENV.DURATION || '2m';
const RAMP_DURATION = __ENV.RAMP_DURATION || '30s';

export const options = {
  scenarios: {
    constant_load: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { duration: RAMP_DURATION, target: TARGET_RPS },  // ramp up to target
        { duration: DURATION, target: TARGET_RPS },       // sustain
        { duration: RAMP_DURATION, target: 0 },           // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],           // <5% errors
    http_req_duration: ['p(95)<3000'],        // 95% under 3s
    job_creation_success_rate: ['rate>0.95'], // >95% success
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Setup: create test users
export function setup() {
  const users = [];
  const numUsers = parseInt(__ENV.NUM_USERS) || 10;

  console.log(`Creating ${numUsers} test users...`);

  for (let i = 0; i < numUsers; i++) {
    const email = `loadtest-${uuidv4()}@test.com`;
    const password = 'testpassword123';

    const registerRes = http.post(
      `${BASE_URL}/auth/register`,
      JSON.stringify({ email, password }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    if (registerRes.status === 201) {
      const authData = JSON.parse(registerRes.body);
      users.push({ token: authData.accessToken, email });
    } else {
      console.error(`Failed to register user ${i}: ${registerRes.status} ${registerRes.body}`);
    }
  }

  console.log(`Setup complete: ${users.length} users registered`);
  console.log(`Target: ${TARGET_RPS} requests/second for ${DURATION}`);
  return { users };
}

// Main test - called at the target rate (300/sec)
export default function (data) {
  if (!data.users || data.users.length === 0) {
    console.error('No users available');
    return;
  }

  const user = data.users[Math.floor(Math.random() * data.users.length)];

  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${user.token}`,
    'Idempotency-Key': uuidv4(),
  };

  const startTime = Date.now();
  const createRes = http.post(
    `${BASE_URL}/jobs`,
    JSON.stringify({
      type: 'sleep',
      payload: { seconds: 1 },
    }),
    { headers }
  );
  const duration = Date.now() - startTime;

  const success = check(createRes, {
    'job created': (r) => r.status === 201,
  });

  jobCreationDuration.add(duration);
  jobCreationRate.add(success);

  if (success) {
    jobsCreated.add(1);
  } else {
    jobCreationErrors.add(1);
    if (createRes.status !== 201) {
      console.error(`Job creation failed: ${createRes.status}`);
    }
  }
  // No sleep - k6 controls the rate via ramping-arrival-rate executor
}

export function handleSummary(data) {
  const metrics = data.metrics;

  let output = '\n=== Load Test Summary (300 RPS Target) ===\n\n';

  const testDuration = (metrics.iteration_duration?.values?.count || 0) > 0
    ? (metrics.http_reqs?.values?.count || 0) / ((Date.now() - data.state?.testRunDurationMs) / 1000)
    : 0;

  output += `Total Requests: ${metrics.http_reqs?.values?.count || 0}\n`;
  output += `Jobs Created: ${metrics.jobs_created?.values?.count || 0}\n`;
  output += `Errors: ${metrics.job_creation_errors?.values?.count || 0}\n`;
  output += `Success Rate: ${((metrics.job_creation_success_rate?.values?.rate || 0) * 100).toFixed(2)}%\n\n`;

  output += `Throughput:\n`;
  output += `  Target: ${TARGET_RPS} req/s\n`;
  output += `  Actual: ${(metrics.http_reqs?.values?.rate || 0).toFixed(2)} req/s\n\n`;

  output += `Response Times:\n`;
  output += `  avg: ${(metrics.http_req_duration?.values?.avg || 0).toFixed(2)}ms\n`;
  output += `  p50: ${(metrics.http_req_duration?.values?.med || 0).toFixed(2)}ms\n`;
  output += `  p95: ${(metrics.http_req_duration?.values?.['p(95)'] || 0).toFixed(2)}ms\n`;
  output += `  p99: ${(metrics.http_req_duration?.values?.['p(99)'] || 0).toFixed(2)}ms\n`;
  output += `  max: ${(metrics.http_req_duration?.values?.max || 0).toFixed(2)}ms\n`;

  return { stdout: output };
}
