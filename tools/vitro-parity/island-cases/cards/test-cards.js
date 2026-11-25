// Made-up custom cards for the island's needsFrontend checks (island-detect.e2e.test.mjs, island-probe.mjs): our own
// code (MIT), plain custom elements, no HA or community card code. Each draws its config's entity in a shadow root.
//
//   vitro-test-dial-card    draws HA's ha-control-circular-slider and ha-big-number: an island lacks both
//   vitro-test-plain-card   draws only what an island defines (ha-card, ha-state-icon, ha-svg-icon)
//   vitro-test-own-card     draws ha-vitro-test-gauge, an ha- name it defines itself
//   vitro-test-lazy-card    draws ha-vitro-test-lazy, which it defines a second after its first render
//   vitro-test-option-card  draws ha-big-number only with `show_number: true`
class VitroTestBase extends HTMLElement {
  setConfig(c) { this._config = c; }
  set hass(h) { this._hass = h; this._render(); }
  _state() { const s = this._hass && this._hass.states[this._config.entity]; return s ? s.state : ''; }
  _render() {
    if (!this.shadowRoot) this.attachShadow({ mode: 'open' });
    this.shadowRoot.innerHTML = `<ha-card style="display:block;padding:16px">${this._body()}<span class="state"></span></ha-card>`;
    this.shadowRoot.querySelector('.state').textContent = this._state();
  }
  getCardSize() { return 2; }
}
customElements.define('vitro-test-dial-card', class extends VitroTestBase {
  _body() { return '<ha-control-circular-slider></ha-control-circular-slider><ha-big-number></ha-big-number>'; }
});
customElements.define('vitro-test-plain-card', class extends VitroTestBase {
  _body() { return '<ha-state-icon></ha-state-icon><ha-svg-icon path="M0 0h24v24H0z"></ha-svg-icon>'; }
});
customElements.define('ha-vitro-test-gauge', class extends HTMLElement {
  connectedCallback() { this.textContent = 'gauge'; }
});
customElements.define('vitro-test-own-card', class extends VitroTestBase {
  _body() { return '<ha-vitro-test-gauge></ha-vitro-test-gauge>'; }
});
customElements.define('vitro-test-lazy-card', class extends VitroTestBase {
  _body() {
    if (!this._timer) this._timer = setTimeout(() => customElements.get('ha-vitro-test-lazy') || customElements.define('ha-vitro-test-lazy', class extends HTMLElement {}), 1000);
    return '<ha-vitro-test-lazy></ha-vitro-test-lazy>';
  }
});
customElements.define('vitro-test-option-card', class extends VitroTestBase {
  _body() { return this._config.show_number ? '<ha-big-number></ha-big-number>' : ''; }
});
