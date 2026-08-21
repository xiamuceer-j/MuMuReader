// Tracks transient iOS Safari/WKWebView viewport changes (address bar
// collapse/restore and pinch zoom). While a transition is active the reader
// must not issue its own scroll corrections, otherwise the browser toolbar
// compensation and the app anchor restoration race and produce a visible jump.
export class ViewportController {
  constructor() {
    this.state = "idle";
    this.timer = null;
    this.pending = new Map();
    this.bound = false;
    this.handleChange = this.handleChange.bind(this);
  }

  get isTransitioning() {
    return this.state === "transitioning";
  }

  handleChange() {
    this.state = "transitioning";
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      this.state = "settled";
      const callbacks = Array.from(this.pending.values());
      this.pending.clear();
      callbacks.forEach(fn => {
        try {
          fn();
        } catch (error) {
          // Never let a deferred callback break the reader.
        }
      });
    }, 300);
  }

  // Run `fn` now when the viewport is already stable, otherwise queue the
  // latest callback for `key` until the transition has settled. A keyed queue
  // prevents stale anchor/progress callbacks from accumulating during a fast
  // Safari toolbar transition.
  onceSettled(fn, key) {
    if (!this.isTransitioning) {
      fn();
      return () => {};
    }
    const pendingKey = key || fn;
    this.pending.set(pendingKey, fn);
    return () => {
      if (this.pending.get(pendingKey) === fn) {
        this.pending.delete(pendingKey);
      }
    };
  }

  bind() {
    if (this.bound) {
      return;
    }
    this.bound = true;
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", this.handleChange);
      window.visualViewport.addEventListener("scroll", this.handleChange);
    }
  }

  unbind() {
    if (!this.bound) {
      return;
    }
    this.bound = false;
    if (window.visualViewport) {
      window.visualViewport.removeEventListener("resize", this.handleChange);
      window.visualViewport.removeEventListener("scroll", this.handleChange);
    }
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.pending.clear();
    this.state = "idle";
  }
}

export const viewportController = new ViewportController();
