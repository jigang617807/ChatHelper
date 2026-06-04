import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, USERNAME, PASSWORD, PHONE, AUTO_REGISTER, formHeaders } from './config.js';

let loggedIn = false;

export function registerIfNeeded() {
  if (!AUTO_REGISTER) {
    return;
  }

  http.post(
    `${BASE_URL}/auth/register-page`,
    {
      username: USERNAME,
      password: PASSWORD,
      phone: PHONE,
    },
    formHeaders()
  );
}

export function login() {
  registerIfNeeded();

  const response = http.post(
    `${BASE_URL}/auth/login-page`,
    {
      username: USERNAME,
      password: PASSWORD,
    },
    {
      ...formHeaders(),
      redirects: 0,
    }
  );

  const ok = check(response, {
    'login redirected to home': (r) => r.status === 302 && String(r.headers.Location || '').includes('/auth/home'),
    'login received session cookie': (r) => Boolean(r.cookies && r.cookies.JSESSIONID && r.cookies.JSESSIONID.length),
  });

  if (!ok) {
    fail(`login failed: status=${response.status}, location=${response.headers.Location || ''}`);
  }

  loggedIn = true;
}

export function ensureLoggedIn() {
  if (!loggedIn) {
    login();
  }
}

export function getPage(path, name) {
  ensureLoggedIn();
  const response = http.get(`${BASE_URL}${path}`);
  check(response, {
    [`${name || path} status is 200`]: (r) => r.status === 200,
  });
  return response;
}

