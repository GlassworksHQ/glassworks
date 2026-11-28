/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Vitro island: a duration sensor's state as HA's frontend writes it ("1h 30m", "2d 6h", "45m 30s").
//
// HA's computeStateDisplay formats the state of a numeric entity whose device class is `duration` and whose unit is
// `min`, `h` or `d` with formatDuration (common/datetime/format_duration.ts, frontend 20261028.5): the value, rounded
// to the entity's display precision, is split into that unit and the next smaller one and written by
// Intl.DurationFormat in its narrow style, the larger unit always shown. Where the browser has no
// Intl.DurationFormat (older Gecko), HA's own fallback writes the same thing with Intl.NumberFormat's units and
// Intl.ListFormat; this is that fallback, unchanged. A value DurationFormat refuses (not a number) throws, and the
// caller falls back to the plain number, as HA does.
//
// A plain script: the island page inlines it (WebIslandCardView.buildIslandHtml), and
// tools/vitro-parity/test/island-duration.test.mjs runs it in Node.

const VITRO_DURATION_UNITS = ['min', 'h', 'd'];

const VITRO_DURATION_FIELDS = [
  ['days', 'day'],
  ['hours', 'hour'],
  ['minutes', 'minute'],
  ['seconds', 'second'],
  ['milliseconds', 'millisecond'],
];

// A field of [duration], 0 when it has none (HA's `duration[field] ?? 0`: a NaN stays NaN, and is refused).
function vitroDurationField(duration, field) {
  const v = duration[field];
  return v === undefined || v === null ? 0 : v;
}

// Throws like Intl.DurationFormat on fractions, NaN and mixed signs.
function vitroIsNegativeDuration(duration) {
  const values = VITRO_DURATION_FIELDS.map(([field]) => Number(vitroDurationField(duration, field)));
  const negative = values.some((value) => value < 0);
  if (!values.every((value) => Number.isInteger(value)) || (negative && values.some((value) => value > 0))) {
    throw new RangeError('Invalid duration');
  }
  return negative;
}

// HA's fallbackUnitsFormat, for its narrow and long styles.
function vitroFallbackUnitsFormat(language, options) {
  const style = options.style === 'narrow' ? 'narrow' : 'long';
  const list = new Intl.ListFormat(language, { type: 'unit', style });
  const unitFormats = VITRO_DURATION_FIELDS.map(([, unit]) => new Intl.NumberFormat(language, { style: 'unit', unit, unitDisplay: style }));
  return (duration) => {
    const negative = vitroIsNegativeDuration(duration);
    const parts = [];
    VITRO_DURATION_FIELDS.forEach(([field], index) => {
      const value = Math.abs(vitroDurationField(duration, field));
      if (value || options[field + 'Display'] === 'always') {
        // Like Intl.DurationFormat, only the first unit shows the sign.
        parts.push(unitFormats[index].format(negative && !parts.length ? -value : value));
      }
    });
    return list.format(parts);
  };
}

// HA's createDurationFormat (the units styles): the browser's DurationFormat, else the fallback. [intl] is a test's
// stand-in for the page's Intl.
function vitroCreateDurationFormat(language, options, intl) {
  const I = intl || Intl;
  if ('DurationFormat' in I) {
    const durationFormat = new I.DurationFormat(language, options);
    return (duration) => durationFormat.format(duration);
  }
  return vitroFallbackUnitsFormat(language, options);
}

const vitroDurationFormats = new Map();

// HA's formatDuration: [duration] (a state string) in [unit] (`min`, `h` or `d`), rounded to [precision] when the
// entity has one, for [locale] (HA's FrontendLocaleData; only its language counts).
function vitroFormatDuration(locale, duration, unit, precision, intl) {
  const raw = parseFloat(duration);
  const value = precision !== undefined && precision !== null ? Math.round(raw * 10 ** precision) / 10 ** precision : raw;
  let input;
  let display;
  switch (unit) {
    case 'd': {
      const days = Math.floor(value);
      input = { days, hours: Math.floor((value - days) * 24) };
      display = 'daysDisplay';
      break;
    }
    case 'h': {
      const hours = Math.floor(value);
      input = { hours, minutes: Math.floor((value - hours) * 60) };
      display = 'hoursDisplay';
      break;
    }
    case 'min': {
      const minutes = Math.floor(value);
      input = { minutes, seconds: Math.floor((value - minutes) * 60) };
      display = 'minutesDisplay';
      break;
    }
    default:
      throw new Error('Invalid duration unit');
  }
  const language = (locale && locale.language) || 'en';
  // One formatter per language and unit, as HA memoizes one per locale.
  const key = language + '|' + unit + (intl ? '|test' : '');
  let format = vitroDurationFormats.get(key);
  if (!format) {
    format = vitroCreateDurationFormat(language, { style: 'narrow', [display]: 'always' }, intl);
    vitroDurationFormats.set(key, format);
  }
  return format(input);
}
