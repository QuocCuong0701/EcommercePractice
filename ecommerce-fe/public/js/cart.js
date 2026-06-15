import { apiFetch } from '/js/api.js';

export async function getCart() {
    return apiFetch('/cart');
}

export async function addToCart(productId, productName, image, price, quantity, maxQuantity) {
    return apiFetch('/cart/items', {
        method: 'POST',
        body: JSON.stringify({ productId, productName, image, price, quantity, maxQuantity }),
    });
}

export async function updateCartItem(productId, quantity) {
    return apiFetch(`/cart/items/${productId}`, {
        method: 'PUT',
        body: JSON.stringify({ quantity }),
    });
}

export async function removeCartItem(productId) {
    return apiFetch(`/cart/items/${productId}`, {
        method: 'DELETE',
    });
}

export async function clearCart() {
    return apiFetch('/cart', {
        method: 'DELETE',
    });
}
