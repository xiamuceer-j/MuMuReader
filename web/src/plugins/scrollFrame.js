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

  getViewportTop() {
    if (this.el) {
      const rect = this.el.getBoundingClientRect();
      return rect.top;
    }
    if (window.visualViewport && window.visualViewport.offsetTop) {
      return window.visualViewport.offsetTop;
    }
    return 0;
  }

  // Return an element's position inside the active scroll frame. Unlike a
  // document coordinate, this remains valid when Safari moves the visual
  // viewport or the scroll frame is nested inside another element.
  viewportOffset(element) {
    if (!element) {
      return 0;
    }
    return element.getBoundingClientRect().top - this.getViewportTop();
  }

  getMaxScrollTop() {
    return Math.max(0, this.getScrollHeight() - this.getViewportHeight());
  }
}

export const scrollFrame = new ScrollFrame();
