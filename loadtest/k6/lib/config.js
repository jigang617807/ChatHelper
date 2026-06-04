export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
export const USERNAME = __ENV.USERNAME || 'loadtest';
export const PASSWORD = __ENV.PASSWORD || 'loadtest123';
export const PHONE = __ENV.PHONE || '18800000000';
export const AUTO_REGISTER = (__ENV.AUTO_REGISTER || 'false').toLowerCase() === 'true';

export function vus(defaultValue) {
  return Number(__ENV.VUS || defaultValue);
}

export function duration(defaultValue) {
  return __ENV.DURATION || defaultValue;
}

export function formHeaders(extra = {}) {
  return {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      ...extra,
    },
  };
}

