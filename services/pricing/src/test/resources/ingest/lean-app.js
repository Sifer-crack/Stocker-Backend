// First-party script: renders the grid (like the real sites do client-side). Must keep running with lean pages on.
const grid = document.getElementById('grid');
for (const n of [1, 2]) {
  grid.insertAdjacentHTML('beforeend', '<div itemscope itemtype="https://schema.org/Product" data-testid="product-500000' + n
    + '-EA-000"><img data-testid="product-image" src="/img' + n + '.png"><p data-testid="product-title">Milk ' + n
    + '</p><p data-testid="product-subtitle">1l</p><meta itemprop="price" content="3.50"></div>');
}
