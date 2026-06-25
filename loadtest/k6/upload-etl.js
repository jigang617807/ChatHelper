import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, formHeaders, vus } from './lib/config.js';
import { ensureLoggedIn } from './lib/session.js';
import { findDocumentRow } from './lib/html.js';

const configuredPdfPath = __ENV.PDF_PATH || 'loadtest/test-data/small.pdf';
const sourceName = configuredPdfPath.split(/[\\/]/).pop() || 'loadtest.pdf';
const pdfBytes = openPdf(configuredPdfPath);
const iterations = Number(__ENV.ITERATIONS || 2);
const pollSeconds = Number(__ENV.ETL_POLL_SECONDS || 2);
const timeoutMs = Number(__ENV.ETL_TIMEOUT_MS || 300000);
const cleanupUploads = (__ENV.CLEANUP_UPLOADS || 'true').toLowerCase() === 'true';
const runId = __ENV.RUN_ID || String(Date.now());

export const options = {
  noCookiesReset: true,
  scenarios: {
    upload_etl: {
      executor: 'per-vu-iterations',
      vus: vus(1),
      iterations,
      maxDuration: __ENV.MAX_DURATION || '30m',
    },
  },
  thresholds: {
    upload_accept_success: ['rate>0.95'],
    etl_success: ['rate>0.90'],
  },
};

const uploadAcceptMs = new Trend('upload_accept_ms', true);
const uploadAcceptSuccess = new Rate('upload_accept_success');
const etlCompleteMs = new Trend('etl_complete_ms', true);
const etlSuccess = new Rate('etl_success');
const etlTimeout = new Rate('etl_timeout');
const etlCompletedDocuments = new Counter('etl_completed_documents');
const etlFailedDocuments = new Counter('etl_failed_documents');

export default function () {
  ensureLoggedIn();
  const testName = `lt-${runId}-vu${__VU}-i${__ITER}-${sourceName}`;

  const response = http.post(
    `${BASE_URL}/doc/upload`,
    { files: http.file(pdfBytes, testName, 'application/pdf') },
    { redirects: 0, timeout: '120s' }
  );

  uploadAcceptMs.add(response.timings.duration);
  const accepted = check(response, {
    'upload accepted and redirected': (r) => r.status === 302 && String(r.headers.Location || '').includes('/doc/list'),
  });
  uploadAcceptSuccess.add(accepted);
  if (!accepted) {
    etlSuccess.add(false);
    etlFailedDocuments.add(1);
    return;
  }

  const acceptedAt = Date.now();
  let documentId = null;
  let finalStatus = null;

  while (Date.now() - acceptedAt <= timeoutMs) {
    const list = http.get(`${BASE_URL}/doc/list`, { timeout: '30s' });
    const row = findDocumentRow(list.body, testName);
    if (row) {
      documentId = row.documentId || documentId;
      finalStatus = row.status;
      if (finalStatus === 'COMPLETED' || finalStatus === 'FAILED') {
        break;
      }
    }
    sleep(pollSeconds);
  }

  const completed = finalStatus === 'COMPLETED';
  const timedOut = finalStatus !== 'COMPLETED' && finalStatus !== 'FAILED';
  etlSuccess.add(completed);
  etlTimeout.add(timedOut);

  if (completed) {
    etlCompleteMs.add(Date.now() - acceptedAt);
    etlCompletedDocuments.add(1);
  } else {
    etlFailedDocuments.add(1);
  }

  if (cleanupUploads && documentId) {
    http.post(`${BASE_URL}/doc/delete`, { docId: documentId }, formHeaders());
  }
}

function openPdf(path) {
  const name = path.split(/[\\/]/).pop();
  const candidates = [path, `loadtest/test-data/${name}`, `../test-data/${name}`];
  let lastError = null;
  for (const candidate of candidates) {
    try {
      return open(candidate, 'b');
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}
