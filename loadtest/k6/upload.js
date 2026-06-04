import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, duration, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';

const configuredPdfPath = __ENV.PDF_PATH || '../test-data/small.pdf';
const pdfName = __ENV.PDF_NAME || configuredPdfPath.split(/[\\/]/).pop() || 'loadtest.pdf';

function candidatePdfPaths(path) {
  const name = path.split(/[\\/]/).pop();
  return [
    path,
    `../test-data/${name}`,
    `loadtest/test-data/${name}`,
  ];
}

function openPdf(path) {
  let lastError = null;
  for (const candidate of candidatePdfPaths(path)) {
    try {
      return open(candidate, 'b');
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

const pdfBytes = openPdf(configuredPdfPath);

export const options = {
  vus: vus(3),
  duration: duration('1m'),
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: [`p(95)<${Number(__ENV.UPLOAD_P95_MS || 30000)}`],
    upload_success: ['rate>0.95'],
  },
};

const uploaded = new Counter('uploaded_documents');
const uploadSuccess = new Rate('upload_success');

export default function () {
  ensureLoggedIn();

  const response = http.post(
    `${BASE_URL}/doc/upload`,
    {
      files: http.file(pdfBytes, `${__VU}-${__ITER}-${pdfName}`, 'application/pdf'),
    },
    {
      redirects: 0,
      timeout: '120s',
    }
  );

  const ok = check(response, {
    'upload redirects to doc list': (r) => r.status === 302 && String(r.headers.Location || '').includes('/doc/list'),
  });

  uploadSuccess.add(ok);
  if (ok) {
    uploaded.add(1);
  }

  sleep(1);
}
