import { isLoggedIn, getUserInfo, logout } from '/js/auth.js';

document.addEventListener('DOMContentLoaded', () => {
    const nav = document.querySelector('.header nav');
    if (!nav) return;

    const loggedIn = isLoggedIn();
    const user = getUserInfo();
    const displayName = user?.fullName || user?.email || '';

    const links = [
        { href: '/products.html', text: 'Sản phẩm', cls: '' },
    ];

    if (loggedIn) {
        links.push({ href: '/cart.html', text: '🛒 Giỏ hàng', cls: '' });
        links.push({ href: '/order-history.html', text: '📋 Đơn hàng', cls: '' });
        links.push({ href: '#', text: `👋 ${displayName}`, cls: '' });
        links.push({ href: '#', text: 'Đăng xuất', cls: 'logout-btn' });
    } else {
        links.push({ href: '/login.html', text: 'Đăng nhập', cls: '' });
        links.push({ href: '/register.html', text: 'Đăng ký', cls: '' });
    }

    nav.innerHTML = links.map(l => {
        const isActive = window.location.pathname === l.href;
        return `<a href="${l.href}" class="${l.cls}${isActive ? ' active' : ''}">${l.text}</a>`;
    }).join('');

    document.querySelector('.logout-btn')?.addEventListener('click', (e) => {
        e.preventDefault();
        logout();
    });
});
