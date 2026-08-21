// Tracks transient iOS Safari/WKWebView viewport changes (address bar
// collapse/restore and pinch zoom). While a transition is active the reader
// must not issue its own scroll corrections, otherwise the browser toolbar
// compensation and the app anchor restoration race and produce a visible jump.
export class ViewportController {
  constructor() {
    this.state = "idle";
    this.timer = null;
    this.pending = [];
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
      const callbacks = this.pending;
      this.pending = [];
      callbacks.forEach(fn => {
        try {
          fn();
        } catch (error) {
          // Never let a deferred callback break the reader.
        }
      });
    }, 300);
  }

  // Run `fn` now when the viewport is already stable, otherwise queue it until
  // the transition has settled. Returns an unsubscribe function.
  onceSettled(fn) {
    if (!this.isTransitioning) {
      fn();
      return () => {};
    }
    this.pending.push(fn);
    return () => {
      const index = this.pending.indexOf(fn);
      if (index >= 0) {
        this.pending.splice(index, 1);
      }
    };
  }

  bind() {
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", this.handleChange);
      window.visualViewport.addEventListener("scroll", this.handleChange);
    }
  }

  unbind() {
    if (window.visualViewport) {
      window.visualViewport.removeEventListener("resize", this.handleChange);
      window.visualViewport.removeEventListener("scroll", this.handleChange);
    }
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.pending = [];
    this.state = "idle";
  }
}

export const viewportController = new ViewportController();