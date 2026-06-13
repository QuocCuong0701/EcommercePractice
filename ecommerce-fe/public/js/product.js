import { apiFetch } from '/js/api.js';

let currentPage = 0;
let totalPages = 0;

document.addEventListener('DOMContentLoaded', () => {
    loadCategories();
    loadProducts();
});

function showLoading() {
    document.getElementById('loading').classList.remove('hidden');
    document.getElementById('error').classList.add('hidden');
}

function hideLoading() {
    document.getElementById('loading').classList.add('hidden');
}

function showError(msg) {
    const el = document.getElementById('error');
    el.textContent = msg;
    el.classList.remove('hidden');
}

async function loadCategories() {
    try {
        const categories = await apiFetch('/categories');
        const select = document.getElementById('categoryFilter');
        categories.forEach(c => {
            const opt = document.createElement('option');
            opt.value = c.id;
            opt.textContent = c.name;
            select.appendChild(opt);
        });
    } catch (err) {
        console.error('Failed to load categories:', err);
    }
}

export async function loadProducts() {
    showLoading();
    document.getElementById('productGrid').innerHTML = '';
    document.getElementById('pagination').innerHTML = '';

    const categoryId = document.getElementById('categoryFilter').value;
    const minPrice = document.getElementById('minPrice').value;
    const maxPrice = document.getElementById('maxPrice').value;
    const query = document.getElementById('searchInput').value.trim();

    let endpoint;
    if (query) {
        const params = new URLSearchParams({ q: query, page: currentPage, size: 20 });
        endpoint = `/products/search?${params}`;
    } else {
        const params = new URLSearchParams({ page: currentPage, size: 20 });
        if (categoryId) params.set('categoryId', categoryId);
        if (minPrice) params.set('minPrice', minPrice);
        if (maxPrice) params.set('maxPrice', maxPrice);
        endpoint = `/products?${params}`;
    }

    try {
        const result = await apiFetch(endpoint);
        hideLoading();
        totalPages = result.totalPages || 0;
        renderProductList(result.content || []);
        renderPagination();
    } catch (err) {
        hideLoading();
        showError(err.message || 'Không thể tải sản phẩm');
    }
}

function renderProductList(products) {
    const grid = document.getElementById('productGrid');
    if (!products.length) {
        grid.innerHTML = '<p class="loading">Không tìm thấy sản phẩm</p>';
        return;
    }

    grid.innerHTML = products.map(p => {
        const discount = p.originalPrice && p.originalPrice > p.price
            ? Math.round((1 - p.price / p.originalPrice) * 100)
            : 0;
        const img = p.image || 'https://placehold.co/400x400/e5e7eb/9ca3af?text=No+Image';

        return `
            <div class="product-card" onclick="window.location.href='/product.html?slug=${p.slug}'">
                <img src="${img}" alt="${p.name}" loading="lazy">
                <div class="product-info">
                    <h3>${p.name}</h3>
                    <div class="price">
                        <span class="current-price">${formatPrice(p.price)}₫</span>
                        ${discount > 0 ? `<span class="original-price">${formatPrice(p.originalPrice)}₫</span>` : ''}
                    </div>
                    ${discount > 0 ? `<span class="discount-badge">-${discount}%</span>` : ''}
                </div>
            </div>
        `;
    }).join('');
}

function renderPagination() {
    const container = document.getElementById('pagination');
    if (totalPages <= 1) return;

    let html = '';
    html += `<button ${currentPage === 0 ? 'disabled' : ''} onclick="goToPage(${currentPage - 1})">‹ Trước</button>`;

    const start = Math.max(0, currentPage - 2);
    const end = Math.min(totalPages - 1, currentPage + 2);

    if (start > 0) {
        html += `<button onclick="goToPage(0)">1</button>`;
        if (start > 1) html += `<span>...</span>`;
    }

    for (let i = start; i <= end; i++) {
        html += `<button class="${i === currentPage ? 'active' : ''}" onclick="goToPage(${i})">${i + 1}</button>`;
    }

    if (end < totalPages - 1) {
        if (end < totalPages - 2) html += `<span>...</span>`;
        html += `<button onclick="goToPage(${totalPages - 1})">${totalPages}</button>`;
    }

    html += `<button ${currentPage >= totalPages - 1 ? 'disabled' : ''} onclick="goToPage(${currentPage + 1})">Sau ›</button>`;

    container.innerHTML = html;
}

function goToPage(page) {
    currentPage = page;
    loadProducts();
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function applyFilters() {
    currentPage = 0;
    loadProducts();
}

function handleSearch(event) {
    if (event.key === 'Enter') {
        currentPage = 0;
        loadProducts();
    }
}

function formatPrice(num) {
    return new Intl.NumberFormat('vi-VN').format(num);
}

window.goToPage = goToPage;
window.applyFilters = applyFilters;
window.handleSearch = handleSearch;