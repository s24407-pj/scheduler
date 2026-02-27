import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      // Don't clear token or redirect for public endpoints
      const url = err.config?.url || '';
      const isPublicEndpoint = url.includes('/public/') || url.includes('/availability') || url.includes('/auth/');
      if (!isPublicEndpoint) {
        localStorage.removeItem('token');
        if (!window.location.pathname.startsWith('/login') && !window.location.pathname.startsWith('/book')) {
          window.location.href = '/login';
        }
      }
    }
    return Promise.reject(err);
  }
);

export default api;

