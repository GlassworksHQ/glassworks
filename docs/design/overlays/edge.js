/*
 * Round 2 of the #691 edge glow: "edge-lit glass". The screen is a thick pane whose edges catch the
 * light. The theme's two lights (a from the top left, b from the bottom right) enter the glass, run
 * along its edges, and flare into a bright caustic where they meet.
 *
 * Three entrances and exits; Spark is chosen (2026-11-20), Sheen and Pour are rejected alternatives kept
 * for reference (the states are shared):
 *   spark  The light starts at the frame's mic, splits both ways round the border, and flares where
 *          the two halves meet. Out: it runs back into the mic and blinks out.
 *   sheen  A sheen of light crosses the whole pane diagonally from the top left, and every edge it
 *          passes catches it. Out: a second, faster sheen switches the edges off behind it.
 *   pour   White-hot light pours in at the bottom, flows up both sides, meets at the top and cools
 *          into the theme's two lights. Out: it cools and drains away to the bottom.
 *
 * Drawn the way the A133 would draw it, so the mockup is honest about cost. Three white sprites
 * (core, glow, haze) are baked once. Each frame stamps them along the border with an alpha, into one
 * layer per light colour; each layer is tinted (SRC_IN) and added to the screen. Flares are baked
 * sprites too. No blur runs per frame: only alpha, translation and scale change.
 *
 * Placeholder data only. Used by mockups.html (scene=lit).
 */
(function () {
  // Where each frame's mic is: a field this proposes for frames/<id>/design/frame.json. Both mics are at
  // the top, in the middle (confirmed by the maintainer, 2026-11-20).
  const MIC = { s1: { edge: "top", at: 0.5 }, mirror: { edge: "top", at: 0.5 } };

  const ENTER = { spark: 650, sheen: 600, pour: 700 };
  const EXIT = { spark: 400, sheen: 350, pour: 450 };

  // One run, then a short one that fails: entrance, listening, thinking, speaking, exit, a gap; then
  // entrance, listening, error, exit, a gap.
  function timeline(v) {
    const E = ENTER[v], X = EXIT[v];
    return [
      ["enter", E, "main"], ["listening", 3600, "main"], ["thinking", 2600, "main"], ["speaking", 3800, "main"], ["exit", X, "main"], ["gap", 700, "main"],
      ["enter", E, "err"], ["listening", 1300, "err"], ["error", 1600, "err"], ["exit", X, "err"], ["gap", 600, "err"],
    ];
  }
  function at(v, t) {
    const tl = timeline(v), total = tl.reduce((n, p) => n + p[1], 0);
    let u = ((t % total) + total) % total, i = 0;
    while (u >= tl[i][1]) { u -= tl[i][1]; i++; }
    return { phase: tl[i][0], u, dur: tl[i][1], run: tl[i][2], prev: tl[(i + tl.length - 1) % tl.length][0], total };
  }
  /** A representative moment of a state, for stills. */
  function stateTime(v, state) {
    const tl = timeline(v), want = { enter: ["enter", 0.55], listening: ["listening", 0.42], thinking: ["thinking", 0.47], speaking: ["speaking", 0.55], error: ["error", 0.3], exit: ["exit", 0.5] }[state];
    let t = 0;
    for (const [p, d, run] of tl) {
      if (p === want[0] && (state !== "listening" || run === "main")) return t + d * want[1];
      t += d;
    }
    return 0;
  }

  const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
  const ease = x => { x = clamp(x); return x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; };
  const easeOut = x => 1 - Math.pow(1 - clamp(x), 3);
  const hash = n => { const s = Math.sin(n * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };

  function hexRgb(c) { const n = parseInt(c.slice(1), 16); return [n >> 16, (n >> 8) & 255, n & 255]; }
  function mixHex(c1, c2, k) { const x = hexRgb(c1), y = hexRgb(c2); return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * k).toString(16).padStart(2, "0")).join(""); }

  // ---------------------------------------------------------------- voice levels (illustrative)
  // Listening: "Turn off the kitchen lights" from 0.3 s to 2.5 s (main run), then quiet.
  function micLevel(u, run) {
    const [a, b] = run === "main" ? [300, 2500] : [150, 900];
    const env = clamp((u - a) / 120) * clamp((b - u) / 160);
    const syl = Math.pow(0.5 + 0.5 * Math.sin(u * 0.021 + 1.9 * Math.sin(u * 0.0053)), 1.6);
    return 0.06 + 0.94 * env * syl;
  }
  // Speaking: the app's TTS pulse (AudioLevel.ttsPulse), shaped into words.
  function ttsLevel(u) {
    const env = clamp(u / 150) * clamp((3500 - u) / 250);
    return env * (0.35 + 0.25 * Math.sin(u * 0.027 + 2 * Math.sin(u * 0.0041))) * (0.7 + 0.6 * Math.pow(0.5 + 0.5 * Math.sin(u * 0.019), 2));
  }

  class Lit {
    constructor(canvas, o) {
      Object.assign(this, o); // W, H, dpr, a, b, dark, variant, frame
      this.c = canvas;
      canvas.width = Math.round(o.W * o.dpr); canvas.height = Math.round(o.H * o.dpr);
      canvas.style.width = o.W + "px"; canvas.style.height = o.H + "px";
      this.g = canvas.getContext("2d");
      this.P = 2 * (o.W + o.H);
      const m = MIC[o.frame] || MIC.s1;
      this.sMic = m.edge === "top" ? m.at * o.W : m.edge === "right" ? o.W + m.at * o.H : m.edge === "bottom" ? o.W + o.H + (1 - m.at) * o.W : 2 * o.W + o.H + (1 - m.at) * o.H;
      this.sBottom = 1.5 * o.W + o.H;
      this.sTop = 0.5 * o.W;
      this.hot = o.dark ? "#ffffff" : mixHex(o.a, o.b, 0.5);
      this.rose = "#fb7185";
      this.layers = [0, 1, 2, 3].map(() => { const c = document.createElement("canvas"); c.width = canvas.width; c.height = canvas.height; return c; });
      this.bake();
    }

    // ------------------------------------------------------------ baking (once)
    bake() {
      const d = this.dpr;
      const dot = r => {
        const s = Math.ceil(r * 2 * d), c = document.createElement("canvas"); c.width = c.height = s;
        const x = c.getContext("2d"), g = x.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2);
        g.addColorStop(0, "rgba(255,255,255,1)"); g.addColorStop(.35, "rgba(255,255,255,.55)"); g.addColorStop(.7, "rgba(255,255,255,.14)"); g.addColorStop(1, "rgba(255,255,255,0)");
        x.fillStyle = g; x.fillRect(0, 0, s, s); return c;
      };
      // core: the edge's bright line; glow: the light caught in the glass; haze: what spills inward
      this.sprites = [{ img: dot(4), r: 4, step: 4, k: 0.6 }, { img: dot(20), r: 20, step: 8, k: 0.22 }, { img: dot(64), r: 64, step: 18, k: 0.11 }];
      this.flares = {};
      for (const [name, col] of [["a", this.a], ["b", this.b], ["hot", this.hot], ["rose", this.rose]]) this.flares[name] = this.bakeFlare(col);
    }
    /** A caustic: a hot core, a soft halo and a thin streak along the edge and a short one across it. */
    bakeFlare(col) {
      const d = this.dpr, R = 120, s = Math.ceil(R * 2 * d), c = document.createElement("canvas"); c.width = c.height = s;
      const x = c.getContext("2d"), m = s / 2, [r, g, b] = hexRgb(col);
      const rad = (rr, stops) => { const gr = x.createRadialGradient(m, m, 0, m, m, rr * d); stops.forEach(([o, a]) => gr.addColorStop(o, `rgba(${r},${g},${b},${a})`)); x.fillStyle = gr; x.fillRect(0, 0, s, s); };
      x.globalCompositeOperation = "lighter";
      rad(R * .55, [[0, .55], [.4, .18], [1, 0]]);
      rad(16, [[0, 1], [.5, .5], [1, 0]]);
      const streak = (w, h, a) => { const gr = x.createLinearGradient(m - w * d, 0, m + w * d, 0); gr.addColorStop(0, `rgba(${r},${g},${b},0)`); gr.addColorStop(.5, `rgba(${r},${g},${b},${a})`); gr.addColorStop(1, `rgba(${r},${g},${b},0)`); x.fillStyle = gr; x.fillRect(m - w * d, m - h * d, 2 * w * d, 2 * h * d); };
      streak(R, 1.6, .9); streak(R * .8, 4, .35);
      x.save(); x.translate(m, m); x.rotate(Math.PI / 2); x.translate(-m, -m); streak(R * .35, 1.4, .6); x.restore();
      return c;
    }

    // ------------------------------------------------------------ geometry
    pos(s) {
      const { W, H, P } = this; s = ((s % P) + P) % P;
      if (s < W) return [s, 0, 0];
      if (s < W + H) return [W, s - W, 1];
      if (s < 2 * W + H) return [W - (s - W - H), H, 2];
      return [0, H - (s - 2 * W - H), 3];
    }
    dist(s, c) { const P = this.P; return ((((s - c) % P) + P * 1.5) % P) - P / 2; }
    diag(x, y) { return clamp((x / this.W + y / this.H) / 2); }

    // ------------------------------------------------------------ the light at a point of the border
    // A field is a function s -> [intensity, wa, wb, whot, wrose] plus a list of flares.
    fieldOf(phase, u, run, dur) {
      const v = this.variant;
      if (phase === "enter") return this[v + "In"](u);
      if (phase === "exit") return this.exitField(u, run);
      if (phase === "listening") return this.listening(u, run);
      if (phase === "thinking") return this.thinking(u);
      if (phase === "speaking") return this.speaking(u);
      if (phase === "error") return this.error(u);
      return { f: () => null, flares: [], p: 0 };
    }

    /** Where the light rests between moments, which each variant leaves it: around, in the corners, or pooled low. */
    posture(x, y) {
      if (this.variant === "sheen") {
        const k = this.diag(x, y), c = Math.exp(-Math.pow(Math.min(k, 1 - k) / 0.16, 2));
        return 0.12 + 0.35 * c;
      }
      if (this.variant === "pour") return 0.1 + 0.3 * Math.pow(y / this.H, 2);
      return 0.2;
    }

    listening(u, run) {
      const L = micLevel(u, run), sMic = this.sMic;
      const ripples = [];
      for (let te = 0; te <= u; te += 140) {
        const lv = micLevel(te, run), age = u - te;
        if (lv > 0.3 && age < 1600) ripples.push([0.62 * age, 0.95 * lv * Math.pow(1 - age / 1600, 1.4)]);
      }
      const f = s => {
        const [x, y] = this.pos(s), k = this.diag(x, y);
        let I = this.posture(x, y) * (1 + 0.5 * L), wa = I * (1 - k), wb = I * k, wh = 0;
        const d = this.dist(s, sMic), sig = 34 + 120 * L, m = (0.3 + 1.0 * L) * Math.exp(-d * d / (2 * sig * sig));
        I += m; wa += m * (1 - k) * (1 - .4 * L); wb += m * k * (1 - .4 * L); wh += m * .4 * L;
        for (const [r, amp] of ripples) {
          const ga = amp * Math.exp(-Math.pow(d + r, 2) / (2 * 22 * 22)), gb = amp * Math.exp(-Math.pow(d - r, 2) / (2 * 22 * 22));
          I += ga + gb; wa += ga; wb += gb;
        }
        return [I, wa, wb, wh, 0];
      };
      const flares = [];
      // the entrance's caustic is still fading as listening starts
      const tail = this[this.variant + "In"](ENTER[this.variant] + u).flares;
      flares.push(...tail);
      return { f, flares, p: 1, thick: 1 + 0.5 * L };
    }

    /** Light trapped in the pane: a and b bounce round the edges the opposite ways, and flare where they cross. Glints catch here and there. */
    thinking(u) {
      const P = this.P, s0 = this.sMic;
      const phi = 1.05 * u + 120 * Math.sin(u * 0.0024);
      const heads = [[s0 - phi, -1, "a"], [s0 + phi, 1, "b"]];
      const f = s => {
        const [x, y] = this.pos(s), k = this.diag(x, y);
        let I = 0.07, wa = I * (1 - k), wb = I * k, wh = 0;
        for (const [h, dir, col] of heads) {
          const d = this.dist(s, h) * dir; // > 0 ahead of the head
          const v = d > 0 ? 1.7 * Math.exp(-d * d / (2 * 28 * 28)) : 1.7 * Math.exp(d / 340) * (0.5 + 0.5 * Math.exp(d / 40));
          I += v; if (col === "a") wa += v; else wb += v;
          wh += v * 0.45 * Math.exp(-d * d / (2 * 16 * 16));
        }
        return [I, wa, wb, wh, 0];
      };
      const flares = [];
      const crossAt = n => { // the time 2*phi(u) passes n*P (bisection; phi rises)
        let lo = 0, hi = 6000; for (let i = 0; i < 40; i++) { const mid = (lo + hi) / 2; (2 * (1.05 * mid + 120 * Math.sin(mid * 0.0024)) < n * P ? lo = mid : hi = mid); } return lo;
      };
      for (let n = 1; n < 6; n++) {
        const tc = crossAt(n), age = u - tc;
        if (age >= 0 && age < 520) flares.push({ s: s0 + n * P / 2, age, life: 520, size: 1.15, col: "hot", amp: 1 });
      }
      for (let j = Math.floor((u - 300) / 90); j <= Math.floor(u / 90); j++) {
        if (j < 0 || hash(j) < 0.45) continue;
        const age = u - j * 90; if (age < 0 || age > 300) continue;
        flares.push({ s: hash(j + 7.3) * P, age, life: 300, size: 0.28, col: hash(j + 2.1) < .5 ? "a" : "b", amp: .9 });
      }
      return { f, flares, p: 1, thick: 1 };
    }

    /**
     * Calm while the reply is on screen (round 2 review: the talk was distracting). The edge holds a
     * soft a-to-b light that rests in its two corners and breathes slowly with the voice, smoothed over
     * 0.7 s, so it never flickers. No pulses and no flares. The bottom edge, under the caption, stays dimmer.
     */
    speaking(u) {
      const { H } = this;
      let L = 0, n = 0;
      for (let te = u - 700; te <= u; te += 50) { L += ttsLevel(Math.max(0, te)); n++; }
      L /= n;
      const f = s => {
        const [x, y] = this.pos(s), k = this.diag(x, y);
        const corner = Math.exp(-Math.pow(Math.min(k, 1 - k) / 0.2, 2));
        const under = y >= H - 0.5 ? 0.6 : 1; // the caption sits over the bottom edge
        const I = (0.34 + 0.36 * corner) * (0.88 + 0.3 * L) * under;
        return [I, I * (1 - k), I * k, 0, 0];
      };
      return { f, flares: [], p: 1, thick: 1 + 0.12 * L };
    }

    /** Rose, a status colour: one rose pulse leaves the mic both ways, then the border holds a dim rose. */
    error(u) {
      const k0 = clamp(u / 240), r = 1.3 * u;
      const f = s => {
        const d = this.dist(s, this.sMic);
        const pulse = 0.9 * clamp(1 - u / 1100) * (Math.exp(-Math.pow(d - r, 2) / (2 * 40 * 40)) + Math.exp(-Math.pow(d + r, 2) / (2 * 40 * 40)));
        const I = 0.12 + 0.2 * k0 * (1 - 0.3 * clamp((u - 600) / 800)) + pulse;
        return [I, (1 - k0) * .12, (1 - k0) * .12, 0, k0 * (I - .12) + pulse];
      };
      return { f, flares: u < 360 ? [{ s: this.sMic, age: u, life: 360, size: .6, col: "rose", amp: 1 }] : [], p: 1, thick: 1 };
    }

    // ------------------------------------------------------------ entrances
    /** spark: ignites at the mic, splits both ways, flares where the halves meet. */
    sparkIn(u) {
      const P = this.P, s0 = this.sMic, half = P / 2;
      const phi = half * ease((u - 90) / 430);
      const f = s => {
        if (u < 90) return [0, 0, 0, 0, 0];
        const [x, y] = this.pos(s), k = this.diag(x, y), d = Math.abs(this.dist(s, s0));
        if (d > phi + 40) return [0, 0, 0, 0, 0];
        const lit = d <= phi ? 1 : Math.exp(-Math.pow(d - phi, 2) / (2 * 18 * 18));
        const tLit = 90 + 430 * invEase(Math.min(1, d / half));
        const after = Math.exp(-(u - tLit) / 260);
        let I = lit * (this.posture(x, y) + 0.55 * after);
        const side = this.dist(s, s0) < 0 ? 0 : 1; // the half going left carries a, the half going right b
        void k;
        const head = phi < half ? 1.2 * Math.exp(-Math.pow(d - phi, 2) / (2 * 24 * 24)) : 0;
        I += head;
        const wa = I * (side === 0 ? 0.8 : 0.2) * (1 - 0.3 * after), wb = I * (side === 0 ? 0.2 : 0.8) * (1 - 0.3 * after);
        return [I, wa, wb, I * 0.3 * after + head * .6, 0];
      };
      const flares = [];
      if (u < 260) flares.push({ s: s0, age: u, life: 260, size: .55 * easeOut(u / 90), col: "hot", amp: 1, grow: true });
      if (u >= 520 && u < 1250) flares.push({ s: s0 + half, age: u - 520, life: 730, size: 1.5, col: "hot", amp: 1 });
      if (u >= 540 && u < 1150) flares.push({ s: s0 + half, age: u - 540, life: 610, size: 1.0, col: "b", amp: .8 });
      return { f, flares, p: ease(u / 650), thick: 1 + 0.4 * clamp(1 - u / 650) };
    }

    /** sheen: a band of light crosses the pane from the top left; each edge catches it as it passes. */
    sheenIn(u) {
      const q = -0.2 + 1.4 * ease(u / 460);
      const f = s => {
        const [x, y] = this.pos(s), g = this.diag(x, y), k = g;
        if (g > q + 0.02) return [0, 0, 0, 0, 0];
        const since = Math.max(0, q - g), spike = Math.exp(-since / 0.07);
        const I = this.posture(x, y) + 0.95 * spike + 0.15 * Math.exp(-since / 0.3);
        return [I, I * (1 - k), I * k, 0.5 * spike * I, 0];
      };
      const qInv = target => { let lo = 0, hi = 460; for (let i = 0; i < 30; i++) { const m = (lo + hi) / 2; (-0.2 + 1.4 * ease(m / 460) < target ? lo = m : hi = m); } return lo; };
      const tTL = qInv(0), tBR = qInv(1), flares = [];
      if (u >= tTL && u < tTL + 520) flares.push({ s: 0, age: u - tTL, life: 520, size: 1.2, col: "a", amp: 1 });
      if (u >= tBR && u < tBR + 650) flares.push({ s: this.W + this.H, age: u - tBR, life: 650, size: 1.35, col: "b", amp: 1 });
      if (u >= tBR && u < tBR + 400) flares.push({ s: this.W + this.H, age: u - tBR, life: 400, size: .8, col: "hot", amp: .8 });
      return { f, flares, p: ease(u / 600), thick: 1, sheen: u < 560 ? { q, a: 0.2 * clamp(1 - (u - 380) / 180) } : null };
    }

    /** pour: white-hot light pours in at the bottom, runs up both sides, meets at the top and cools. */
    pourIn(u) {
      const P = this.P, s0 = this.sBottom, half = P / 2;
      const phi = half * ease((u - 120) / 480);
      const f = s => {
        if (u < 40) return [0, 0, 0, 0, 0];
        const [x, y] = this.pos(s), k = this.diag(x, y), d = Math.abs(this.dist(s, s0));
        const front = Math.max(phi, 30 * easeOut(u / 120));
        if (d > front + 50) return [0, 0, 0, 0, 0];
        const lit = d <= front ? 1 : Math.exp(-Math.pow(d - front, 2) / (2 * 22 * 22));
        const tLit = d < 30 ? 40 : 120 + 480 * invEase(Math.min(1, d / half));
        const hot = Math.exp(-Math.max(0, u - tLit) / 240);
        const head = phi < half ? 1.3 * Math.exp(-Math.pow(d - phi, 2) / (2 * 34 * 34)) : 0;
        const I = lit * (this.posture(x, y) + 0.9 * hot) + head;
        const cool = I * (1 - hot);
        return [I, cool * (1 - k), cool * k, I * hot + head * .5, 0];
      };
      const flares = [];
      if (u < 380) flares.push({ s: s0, age: u, life: 380, size: .9 * easeOut(u / 120), col: "hot", amp: 1, grow: true });
      if (u >= 600 && u < 1300) flares.push({ s: this.sTop, age: u - 600, life: 700, size: 1.4, col: "hot", amp: 1 });
      if (u >= 640 && u < 1200) flares.push({ s: this.sTop, age: u - 640, life: 560, size: 1, col: "a", amp: .8 });
      return { f, flares, p: ease(u / 700), thick: 1 + 0.5 * Math.exp(-Math.max(0, u - 300) / 300) };
    }

    // ------------------------------------------------------------ exits
    exitField(u, run) {
      const v = this.variant, X = EXIT[v], prev = run === "err" ? this.error(1600) : this.speaking(3800);
      const base = run === "err" ? this.error(1600 + u) : this.speaking(3800 + u);
      let mask, flares = [], sheen = null;
      if (v === "spark") {
        const rho = (this.P / 2 + 40) * (1 - ease(u / (X - 80)));
        mask = s => { const d = Math.abs(this.dist(s, this.sMic)); return d < rho ? 1 : Math.exp(-Math.pow(d - rho, 2) / (2 * 16 * 16)); };
        const bead = s => { const d = Math.abs(this.dist(s, this.sMic)); return u < X - 80 ? 0.9 * Math.exp(-Math.pow(d - rho, 2) / (2 * 20 * 20)) : 0; };
        const fb = s => { const r = base.f(s), m = mask(s), b = bead(s); return [r[0] * m + b, r[1] * m + b * .5, r[2] * m + b * .5, r[3] * m + b * .4, r[4] * m]; };
        if (u > X - 120) flares.push({ s: this.sMic, age: u - (X - 120), life: 160, size: .5, col: run === "err" ? "rose" : "hot", amp: 1 });
        return { f: fb, flares, p: 1 - ease(u / X), thick: 1 };
      }
      if (v === "sheen") {
        const q = -0.2 + 1.4 * ease(u / (X - 30));
        const fb = s => {
          const [x, y] = this.pos(s), g = this.diag(x, y), r = base.f(s);
          if (g < q - 0.01) return [0, 0, 0, 0, 0];
          const spike = Math.exp(-Math.max(0, g - q) / 0.05) * 0.8;
          return [r[0] + spike, r[1] + spike * (1 - g), r[2] + spike * g, r[3] + spike * .4, r[4]];
        };
        sheen = { q, a: 0.12 };
        return { f: fb, flares, p: 1 - ease(u / X), thick: 1, sheen };
      }
      // pour: cools and drains to the bottom
      const psi = (this.P / 2 + 30) * ease(u / (X - 50));
      const fb = s => {
        const d = Math.abs(this.dist(s, this.sTop)), r = base.f(s), m = d > psi ? 1 : Math.exp(-Math.pow(psi - d, 2) / (2 * 26 * 26)), cool = 1 - 0.5 * (u / X);
        return [r[0] * m * cool, r[1] * m * cool, r[2] * m * cool, 0, r[4] * m * cool];
      };
      return { f: fb, flares, p: 1 - ease(u / X), thick: 1 };
    }

    // ------------------------------------------------------------ one frame
    frameAt(t) {
      const ph = at(this.variant, t);
      let fl = this.fieldOf(ph.phase, ph.u, ph.run, ph.dur);
      // cross-fade between states (300 ms), not across the entrance or exit, which carry their own motion
      const XF = 300;
      if (ph.u < XF && ["thinking", "speaking", "error"].includes(ph.phase)) {
        const prevDur = timeline(this.variant).find(p => p[0] === ph.prev)[1];
        const prev = this.fieldOf(ph.prev, prevDur + ph.u, ph.run, prevDur), k = ease(ph.u / XF);
        const a = fl, b = prev;
        fl = { f: s => { const x = a.f(s), y = b.f(s); return x.map((v, i) => v * k + y[i] * (1 - k)); }, flares: [...a.flares, ...b.flares.map(f => ({ ...f, amp: f.amp * (1 - k) }))], p: 1, thick: a.thick * k + b.thick * (1 - k) };
      }
      return { ph, fl };
    }

    draw(t) {
      const { g, dpr, W, H, layers, sprites } = this;
      const { ph, fl } = this.frameAt(t);
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.clearRect(0, 0, this.c.width, this.c.height);
      if (ph.phase === "gap") return ph;
      const cols = [this.a, this.b, this.hot, this.rose];
      const lg = layers.map(c => { const x = c.getContext("2d"); x.globalCompositeOperation = "source-over"; x.clearRect(0, 0, c.width, c.height); x.globalCompositeOperation = "lighter"; return x; });
      const thick = fl.thick || 1;
      for (const sp of sprites) {
        const size = sp.r * 2 * dpr * (sp === sprites[0] ? 1 : thick);
        for (let s = 0; s < this.P; s += sp.step) {
          const r = fl.f(s); if (!r || r[0] < 0.004) continue;
          const [x, y] = this.pos(s), tot = r[1] + r[2] + r[3] + r[4] || 1;
          for (let i = 0; i < 4; i++) {
            const a = clamp(r[0] * (r[i + 1] / tot) * sp.k * (this.dark ? 1 : 1.25), 0, 1);
            if (a < 0.003) continue;
            lg[i].globalAlpha = a;
            lg[i].drawImage(sp.img, x * dpr - size / 2, y * dpr - size / 2, size, size);
          }
        }
      }
      // tint each layer (SRC_IN) and add it to the screen
      g.globalCompositeOperation = this.dark ? "lighter" : "source-over";
      layers.forEach((c, i) => {
        const x = lg[i]; x.globalAlpha = 1; x.globalCompositeOperation = "source-in"; x.fillStyle = cols[i]; x.fillRect(0, 0, c.width, c.height);
        g.drawImage(c, 0, 0);
      });
      // the sheen: a baked diagonal band, translated across the pane
      if (fl.sheen && fl.sheen.a > 0) {
        const q = fl.sheen.q, w = 0.16;
        const p0 = this.diagPoint(q - w), p1 = this.diagPoint(q + w);
        const gr = g.createLinearGradient(p0[0] * dpr, p0[1] * dpr, p1[0] * dpr, p1[1] * dpr);
        const al = fl.sheen.a * (this.dark ? 1 : 1.4), [ar, ag, ab] = hexRgb(this.a), [br, bg, bb] = hexRgb(this.b);
        gr.addColorStop(0, `rgba(${ar},${ag},${ab},0)`); gr.addColorStop(.42, `rgba(${ar},${ag},${ab},${al * .6})`);
        gr.addColorStop(.5, this.dark ? `rgba(255,255,255,${al})` : `rgba(${br},${bg},${bb},${al * .7})`);
        gr.addColorStop(.58, `rgba(${br},${bg},${bb},${al * .6})`); gr.addColorStop(1, `rgba(${br},${bg},${bb},0)`);
        g.fillStyle = gr; g.fillRect(0, 0, this.c.width, this.c.height);
      }
      // the bevel: the inner edge of a thick pane catching the light
      const p = fl.p;
      if (p > 0) {
        const gr = g.createLinearGradient(0, 0, W * dpr, H * dpr);
        gr.addColorStop(0, this.a); gr.addColorStop(1, this.b);
        g.globalAlpha = (this.dark ? 0.2 : 0.3) * p; g.strokeStyle = gr; g.lineWidth = 1 * dpr;
        const inset = 7 * dpr; g.beginPath(); g.roundRect(inset, inset, this.c.width - 2 * inset, this.c.height - 2 * inset, 10 * dpr); g.stroke();
        g.globalAlpha = 1;
      }
      // caustics
      g.globalCompositeOperation = this.dark ? "lighter" : "source-over";
      for (const f of fl.flares) {
        const k = f.age / f.life; if (k < 0 || k >= 1) continue;
        const a = f.amp * (f.grow ? (1 - Math.pow(k, 2)) : Math.pow(1 - k, 1.6));
        const sc = f.size * (f.grow ? 1 : 0.7 + 0.5 * easeOut(k));
        const img = this.flares[f.col] || this.flares.hot;
        const [x, y, edge] = this.pos(f.s), sz = img.width * sc;
        g.save(); g.globalAlpha = clamp(a * (this.dark ? 1 : 0.85)); g.translate(x * dpr, y * dpr); if (edge % 2 === 1) g.rotate(Math.PI / 2);
        g.drawImage(img, -sz / 2, -sz / 2, sz, sz); g.restore();
      }
      g.globalCompositeOperation = "source-over";
      return { ...ph, p };
    }
    /** The point on the sheen's axis where diag() is q: the axis runs along the gradient of diag(). */
    diagPoint(q) { const l = 2 * q / (1 / (this.W * this.W) + 1 / (this.H * this.H)); return [l / this.W, l / this.H]; }
  }

  function invEase(y) { let lo = 0, hi = 1; for (let i = 0; i < 24; i++) { const m = (lo + hi) / 2; ease(m) < y ? lo = m : hi = m; } return lo; }

  window.Lit = { Lit, at, timeline, stateTime, micLevel, ENTER, EXIT, MIC };
})();
