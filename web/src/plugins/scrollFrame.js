// Unified scroll coordinate access for the reader.
//
// The reader historically read/wrote `document.scrollingElement` (the root
// scroller). Keeping all scroll math behind this frame makes it possible to
// later move scrolling into a dedicated container without changing the
// business logic that computes anchors and positions.
export class ScrollFrame {
  constructor() {
    this.el = null;
  }

  // Bind an explicit scroller when the reader is hosted inside one; otherwise
  // fall back to the document root scroller.
  bind(el) {
    this.el = el || null;
    return this;
  }

  getElement() {
    return (
      this.el ||
      document.scrollingElement ||
      document.documentElement ||
      document.body ||
      null
    );
  }

  getScrollTop() {
    const el = this.getElement();
    return el ? el.scrollTop : 0;
  }

  setScrollTop(top) {
    const el = this.getElement();
    if (el) {
      el.scrollTop = top;
    }
  }

  getScrollHeight() {
    const el = this.getElement();
    return el ? el.scrollHeight : 0;
  }

  // The height of the region that is currently scrollable/visible.
  getViewportHeight() {
    if (this.el) {
      return this.el.clientHeight || 0;
    }
    if (window.visualViewport && window.visualViewport.height) {
      return window.visualViewport.height;
    }
    return window.innerHeight || 0;
  }

  // Document-relative top of an element. This stays stable across Safari
  // address-bar transitions because it is `rect.top + scrollTop`, not the raw
  // viewport-relative `rect.top` alone.
  documentTop(element) {
    if (!element) {
      return 0;
    }
    return element.getBoundingClientRect().top + this.getScrollTop();
  }
}

export const scrollFrame = new ScrollFrame();