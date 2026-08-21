// Single root-page scroll coordinate access for the reader. Keeping all reads
// and writes behind this frame prevents individual reader modes from creating
// their own competing scroll containers.
export class ScrollFrame {
  constructor() {
    this.bound = false;
  }

  // Kept as a lifecycle hook for Reader. The argument is intentionally
  // ignored: the project has one supported vertical scroller, the document.
  bind() {
    this.bound = true;
    return this;
  }

  getElement() {
    return (
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
    if (!el || !Number.isFinite(top)) {
      return;
    }
    el.scrollTop = Math.max(0, top);
  }

  getScrollHeight() {
    const el = this.getElement();
    return el ? el.scrollHeight : 0;
  }

  // The height of the region that is currently scrollable/visible.
  getViewportHeight() {
    // For the document scroller use the layout viewport. The visual viewport
    // changes while Safari's toolbar animates and must not alter page scroll
    // calculations or trigger a second application correction.
    return window.innerHeight || 0;
  }

  getViewportTop() {
    return 0;
  }

  // Return an element's position inside the active scroll frame. Unlike a
  // document coordinate, this remains valid when Safari moves the visual
  // viewport or the scroll frame is nested inside another element.
  viewportOffset(element) {
    if (!element) {
      return 0;
    }
    return element.getBoundingClientRect().top;
  }

  getMaxScrollTop() {
    const scrollHeight = this.getScrollHeight();
    const viewportHeight = this.getViewportHeight();
    if (!scrollHeight || !viewportHeight) {
      return null;
    }
    return Math.max(0, scrollHeight - viewportHeight);
  }
}

export const scrollFrame = new ScrollFrame();
