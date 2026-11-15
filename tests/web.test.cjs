const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const html = fs.readFileSync(path.join(__dirname, '../web/index.html'), 'utf8');
const script = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];

function element(attributes = {}) {
  const classes = new Set();
  return {
    attributes: { ...attributes }, listeners: {}, textContent: '', disabled: false,
    classList: {
      add: (...values) => values.forEach(value => classes.add(value)),
      remove: (...values) => values.forEach(value => classes.delete(value)),
      contains: value => classes.has(value),
      toggle(value, force) {
        const enabled = force === undefined ? !classes.has(value) : force;
        if (enabled) classes.add(value); else classes.delete(value);
        return enabled;
      },
    },
    getAttribute(key) { return this.attributes[key] ?? null; },
    setAttribute(key, value) { this.attributes[key] = value; },
    removeAttribute(key) { delete this.attributes[key]; },
    addEventListener(event, listener) { this.listeners[event] = listener; },
    click() { this.listeners.click?.call(this); },
  };
}

function page(options = {}) {
  const ids = Object.fromEntries(['phone', 'clock', 'm-check', 'm-status', 'year'].map(id => [id, element()]));
  const label = element();
  ids['m-check'].querySelector = () => label;
  const buttons = ['light', 'dark'].map(mode => element({ 'data-mode': mode }));
  const reveals = Array.from({ length: 3 }, () => element());
  const timers = [];
  const observed = [];
  let observer;
  let disconnected = false;
  let schemeListener;
  let saved = options.saved ?? null;
  const scheme = {
    matches: options.dark ?? false,
    addEventListener(event, listener) { schemeListener = listener; },
  };
  const context = {
    document: {
      getElementById: id => ids[id],
      querySelectorAll: selector => selector === '.reveal' ? reveals : buttons,
    },
    localStorage: {
      getItem() { if (options.storageBlocked) throw new Error('storage disabled'); return saved; },
      setItem(key, value) { if (options.storageBlocked) throw new Error('storage disabled'); saved = value; },
    },
    setTimeout: callback => timers.push(callback),
    setInterval: () => 1,
    window: {
      matchMedia: query => query.includes('reduced-motion') ? { matches: !!options.reducedMotion } : scheme,
    },
  };
  if (options.observer !== false) {
    context.IntersectionObserver = class {
      constructor(callback, settings) {
        if (options.constructorThrows) throw new Error('observer unavailable');
        observer = { callback, settings };
      }
      observe(target) {
        if (options.observeThrows && observed.length === 1) throw new Error('observe failed');
        observed.push(target);
      }
      unobserve(target) { observed.splice(observed.indexOf(target), 1); }
      disconnect() { disconnected = true; }
    };
    context.window.IntersectionObserver = context.IntersectionObserver;
  }
  vm.runInNewContext(script, context);
  return {
    ids, buttons, reveals, observed, observer, timers, label,
    disconnected: () => disconnected,
    changeScheme: matches => schemeListener?.({ matches }),
  };
}

test('page content is visible by default when JavaScript is blocked', () => {
  const base = html.match(/\.reveal\s*\{([^}]+)\}/)[1];
  assert.doesNotMatch(base, /opacity\s*:\s*0(?:\D|$)|visibility\s*:\s*hidden|display\s*:\s*none/);
});

test('unsupported IntersectionObserver leaves every section visible', () => {
  const view = page({ observer: false });
  assert.ok(view.reveals.every(target => !target.classList.contains('reveal-pending')));
  assert.notEqual(view.ids.year.textContent, '');
});

test('reduced motion does not create an observer or hide content', () => {
  const view = page({ reducedMotion: true });
  assert.equal(view.observer, undefined);
  assert.ok(view.reveals.every(target => !target.classList.contains('reveal-pending')));
});

test('only observed elements become pending and are released on intersection', () => {
  const view = page();
  assert.equal(view.observed.length, 3);
  assert.ok(view.reveals.every(target => target.classList.contains('reveal-pending')));
  const target = view.reveals[0];
  view.observer.callback([{ target, isIntersecting: true }]);
  assert.ok(!target.classList.contains('reveal-pending'));
  assert.equal(view.observed.length, 2);
});

test('tall sections can reveal on their first intersecting pixel', () => {
  assert.equal(page().observer.settings.threshold, 0);
});

test('observer constructor failure does not hide the page or break later initialization', () => {
  const view = page({ constructorThrows: true });
  assert.ok(view.reveals.every(target => !target.classList.contains('reveal-pending')));
  assert.notEqual(view.ids.year.textContent, '');
});

test('partially failed observation clears pending state and disconnects', () => {
  const view = page({ observeThrows: true });
  assert.ok(view.reveals.every(target => !target.classList.contains('reveal-pending')));
  assert.ok(view.disconnected());
});

test('preview check completes and enables a subsequent check', () => {
  const view = page();
  view.ids['m-check'].click();
  view.ids['m-check'].click();
  assert.equal(view.timers.length, 1);
  assert.ok(view.ids['m-check'].disabled);
  assert.equal(view.ids['m-check'].attributes['aria-busy'], 'true');
  view.timers[0]();
  assert.equal(view.ids['m-check'].disabled, false);
  assert.equal(view.ids['m-check'].getAttribute('aria-busy'), null);
  view.ids['m-check'].click();
  assert.equal(view.timers.length, 2);
});

test('automatic mode follows system scheme changes', () => {
  const view = page();
  view.changeScheme(true);
  assert.ok(view.ids.phone.classList.contains('dark'));
  view.changeScheme(false);
  assert.ok(!view.ids.phone.classList.contains('dark'));
});

test('persisted theme overrides system preference', () => {
  const view = page({ saved: 'dark' });
  view.changeScheme(false);
  assert.ok(view.ids.phone.classList.contains('dark'));
});

test('invalid saved preference still follows the system', () => {
  const view = page({ saved: 'invalid' });
  view.changeScheme(true);
  assert.ok(view.ids.phone.classList.contains('dark'));
});

test('manual theme survives scheme changes when storage is blocked', () => {
  const view = page({ storageBlocked: true, dark: true });
  view.buttons[0].click();
  view.changeScheme(true);
  assert.ok(!view.ids.phone.classList.contains('dark'));
  assert.equal(view.buttons[0].getAttribute('aria-pressed'), 'true');
  assert.equal(view.buttons[1].getAttribute('aria-pressed'), 'false');
  view.buttons[1].click();
  view.changeScheme(false);
  assert.ok(view.ids.phone.classList.contains('dark'));
});
