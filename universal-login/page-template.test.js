// The template is text until Auth0 renders it: nothing but a check like this catches a tag
// that silently becomes page content. Jinja's {#- -#} comment did exactly that once.
const { test } = require('node:test');
const assert = require('node:assert');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const template = readFileSync(join(__dirname, 'page-template.liquid'), 'utf8');
const theme = JSON.parse(readFileSync(join(__dirname, 'theme.json'), 'utf8'));

test('the template carries the two tags Auth0 requires', () => {
    assert.match(template, /\{%-\s*auth0:head\s*-%\}/);
    assert.match(template, /\{%-\s*auth0:widget\s*-%\}/);
});

test('the template holds no non-Liquid comment', () => {
    assert.ok(!template.includes('{#'), 'Liquid comments are {% comment %}, not {# #}');
});

test('every Liquid block is closed', () => {
    const count = (re) => (template.match(re) || []).length;
    assert.strictEqual(count(/\{%\s*if\s/g), count(/\{%\s*endif\s*%\}/g));
    assert.strictEqual(count(/\{%\s*comment\s*%\}/g), count(/\{%\s*endcomment\s*%\}/g));
});

test('the theme states every group the Branding API requires', () => {
    for (const group of ['borders', 'colors', 'fonts', 'page_background', 'widget']) {
        assert.ok(theme[group], `missing ${group}`);
    }
});
