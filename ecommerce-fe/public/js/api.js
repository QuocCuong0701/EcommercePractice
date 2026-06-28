const API_BASE = '/api/v1';

export async function apiFetch(path, options = {}) {
    const token = localStorage.getItem('accessToken');
    const headers = {
        'Content-Type': 'application/json',
        ...(token && { 'Authorization': `Bearer ${token}` }),
        ...options.headers,
    };

    const res = await fetch(API_BASE + path, { ...options, headers });

    if (res.status === 401) {
        const refreshed = await refreshToken();
        if (refreshed) return apiFetch(path, options);
        window.location.href = '/login.html';
    }

    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `API Error: ${res.status}`);
    }

    return res.json();
}

async function refreshToken() {
    const refreshToken = localStorage.getItem('refreshToken');
    if (!refreshToken) return false;

    try {
        const res = await fetch(API_BASE + '/auth/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken }),
        });
        if (!res.ok) return false;
        const data = await res.json();
        localStorage.setItem('accessToken', data.accessToken);
        localStorage.setItem('refreshToken', data.refreshToken);
        return true;
    } catch {
        return false;
    }
}

export function formatVND(amount) {
    return new Intl.NumberFormat('vi-VN', {
        style: 'currency',
        currency: 'VND',
    }).format(amount);
}

export function statusText(status) {
    const map = {
        PENDING_PAYMENT: 'Chờ thanh toán',
        PAID: 'Đã thanh toán',
        PROCESSING: 'Đang xử lý',
        SHIPPED: 'Đang giao',
        DELIVERED: 'Đã giao',
        CANCELLED: 'Đã hủy',
        REFUNDED: 'Đã hoàn tiền',
    };
    return map[status] || status;
}