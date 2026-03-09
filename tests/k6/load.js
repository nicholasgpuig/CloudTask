import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const jobsCreated = new Counter('jobs_created');
const jobCreationErrors = new Counter('job_creation_errors');
const jobCreationRate = new Rate('job_creation_success_rate');
const jobCreationDuration = new Trend('job_creation_duration');

// Load test configuration - override with environment variables
export const options = {
  stages: [
    { duration: __ENV.RAMP_UP || '30s', target: parseInt(__ENV.VUS) || 10 },     // ramp up
    { duration: __ENV.DURATION || '2m', target: parseInt(__ENV.VUS) || 10 },     // sustain
    { duration: __ENV.RAMP_DOWN || '30s', target: 0 },                           // ramp down
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],         // <5% errors
    http_req_duration: ['p(95)<3000'],      // 95% under 3s
    job_creation_success_rate: ['rate>0.95'], // >95% success
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Setup: create multiple test users for load distribution
export function setup() {
  const users = [];
  const numUsers = parseInt(__ENV.NUM_USERS) || 5;

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
      users.push({ token: authData.token, email });
    } else {
      console.error(`Failed to register user ${i}: ${registerRes.status}`);
    }
  }

  console.log(`Setup complete: ${users.length} users registered`);
  return { users };
}

// Main test scenario
export default function (data) {
  if (!data.users || data.users.length === 0) {
    console.error('No users available');
    return;
  }

  // Pick a random user for this iteration
  const user = data.users[Math.floor(Math.random() * data.users.length)];

  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${user.token}`,
    'Idempotency-Key': uuidv4(),
  };

  // Create a job
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
    const job = JSON.parse(createRes.body);

    // Optionally poll for status (simulates real user behavior)
    if (Math.random() < 0.3) { // 30% of users poll
      sleep(0.5);
      http.get(`${BASE_URL}/jobs/${job.id}`, { headers });
    }
  } else {
    jobCreationErrors.add(1);
    console.error(`Job creation failed: ${createRes.status} ${createRes.body}`);
  }

  // Think time between requests
  sleep(Math.random() * 2 + 0.5); // 0.5-2.5s random delay
}

// Summary output
export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}

function textSummary(data, options) {
  const metrics = data.metrics;

  let output = '\n=== Load Test Summary ===\n\n';

  output += `Total Requests: ${metrics.http_reqs?.values?.count || 0}\n`;
  output += `Jobs Created: ${metrics.jobs_created?.values?.count || 0}\n`;
  output += `Errors: ${metrics.job_creation_errors?.values?.count || 0}\n`;
  output += `Success Rate: ${((metrics.job_creation_success_rate?.values?.rate || 0) * 100).toFixed(2)}%\n\n`;

  output += `Response Times:\n`;
  output += `  avg: ${(metrics.http_req_duration?.values?.avg || 0).toFixed(2)}ms\n`;
  output += `  p95: ${(metrics.http_req_duration?.values?.['p(95)'] || 0).toFixed(2)}ms\n`;
  output += `  max: ${(metrics.http_req_duration?.values?.max || 0).toFixed(2)}ms\n`;

  return output;
}
