import { apiFetch } from '/js/api.js';

export function isLoggedIn() {
    return !!localStorage.getItem('accessToken');
}

export function getUserInfo() {
    const email = localStorage.getItem('userEmail');
    const fullName = localStorage.getItem('userFullName');
    return email ? { email, fullName } : null;
}

export async function login(email, password) {
    const data = await apiFetch('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
    });
    saveSession(data);
    return data;
}

export async function register(email, password, fullName) {
    const data = await apiFetch('/auth/register', {
        method: 'POST',
        body: JSON.stringify({ email, password, fullName }),
    });
    saveSession(data);
    return data;
}

export async function logout() {
    const refreshToken = localStorage.getItem('refreshToken');
    try {
        await apiFetch('/auth/logout', {
            method: 'POST',
            body: JSON.stringify({ refreshToken }),
        });
    } catch (e) { /* ignore */ }
    clearSession();
    window.location.href = '/';
}

function saveSession(data) {
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken);
    localStorage.setItem('userEmail', data.email);
}

function clearSession() {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('userEmail');
    localStorage.removeItem('userFullName');
}
