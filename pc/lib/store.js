'use strict';
// Small JSON file store with debounced writes.
const fs = require('fs');
const path = require('path');

class JsonStore {
  constructor(file, defaults) {
    this.file = file;
    this.timer = null;
    this.data = Object.assign({}, defaults || {});
    try {
      const raw = fs.readFileSync(file, 'utf8');
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object') this.data = Object.assign(this.data, parsed);
    } catch (e) { /* first run */ }
  }
  save() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.flush(), 400);
  }
  flush() {
    clearTimeout(this.timer);
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(this.data));
      fs.renameSync(tmp, this.file);
    } catch (e) { console.error('store write failed', this.file, e.message); }
  }
}
module.exports = { JsonStore };
