import { useEffect } from "react";
import { Link } from "react-router-dom";
import {
  ArrowRight,
  BarChart3,
  Bot,
  Check,
  Database,
  FileSpreadsheet,
  LockKeyhole,
  MessageSquareText,
  Search,
  ShieldCheck,
  Sparkles,
  TableProperties,
  Zap,
} from "lucide-react";
import styles from "./LandingPage.module.css";

const pageTitle = "QueryMate – Hỏi dữ liệu bằng tiếng Việt với AI";
const pageDescription =
  "QueryMate giúp bạn kết nối MySQL, PostgreSQL hoặc Excel, đặt câu hỏi bằng tiếng Việt và nhận truy vấn SQL an toàn cùng biểu đồ trực quan.";

function upsertMeta(selector: string, attributes: Record<string, string>) {
  let element = document.head.querySelector<HTMLMetaElement>(selector);
  if (!element) {
    element = document.createElement("meta");
    document.head.appendChild(element);
  }
  Object.entries(attributes).forEach(([name, value]) =>
    element?.setAttribute(name, value),
  );
}

export function LandingPage() {
  useEffect(() => {
    document.title = pageTitle;
    upsertMeta('meta[name="description"]', {
      name: "description",
      content: pageDescription,
    });
    upsertMeta('meta[property="og:title"]', {
      property: "og:title",
      content: pageTitle,
    });
    upsertMeta('meta[property="og:description"]', {
      property: "og:description",
      content: pageDescription,
    });

    const configuredSiteUrl = import.meta.env.VITE_PUBLIC_SITE_URL?.trim();
    if (configuredSiteUrl) {
      const canonicalUrl = new URL("/", configuredSiteUrl).toString();
      let canonical = document.head.querySelector<HTMLLinkElement>(
        'link[rel="canonical"]',
      );
      if (!canonical) {
        canonical = document.createElement("link");
        canonical.rel = "canonical";
        document.head.appendChild(canonical);
      }
      canonical.href = canonicalUrl;
      upsertMeta('meta[property="og:url"]', {
        property: "og:url",
        content: canonicalUrl,
      });
    }

    return () => {
      document.title = "QueryMate – AI Database Assistant";
    };
  }, []);

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <a className={styles.brand} href="#top" aria-label="QueryMate - Trang chủ">
          <span>AI</span>
          QueryMate
        </a>
        <nav className={styles.nav} aria-label="Điều hướng trang giới thiệu">
          <a href="#tinh-nang">Tính năng</a>
          <a href="#cach-hoat-dong">Cách hoạt động</a>
          <a href="#bao-mat">Bảo mật</a>
        </nav>
        <div className={styles.headerActions}>
          <Link className={styles.loginLink} to="/login">
            Đăng nhập
          </Link>
          <Link className={styles.compactCta} to="/register">
            Đăng ký miễn phí
          </Link>
        </div>
      </header>

      <main id="top">
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={styles.heroCopy}>
            <p className={styles.eyebrow}>
              <Sparkles size={15} aria-hidden="true" /> Trợ lý dữ liệu sử dụng Gemini AI
            </p>
            <h1 id="hero-title">
              Đặt câu hỏi bằng tiếng Việt. <span>Hiểu dữ liệu ngay.</span>
            </h1>
            <p className={styles.lead}>
              Kết nối dữ liệu, mô tả điều bạn muốn biết và để QueryMate tạo SQL,
              kiểm tra an toàn, trả kết quả cùng biểu đồ dễ hiểu.
            </p>
            <div className={styles.heroActions}>
              <Link className={styles.primaryCta} to="/register">
                Đăng ký miễn phí <ArrowRight size={18} aria-hidden="true" />
              </Link>
              <a className={styles.secondaryCta} href="#cach-hoat-dong">
                Xem cách hoạt động
              </a>
            </div>
            <ul className={styles.heroChecks} aria-label="Lợi ích chính">
              <li><Check size={16} aria-hidden="true" /> Không cần viết SQL thủ công</li>
              <li><Check size={16} aria-hidden="true" /> Kiểm tra truy vấn chỉ đọc</li>
              <li><Check size={16} aria-hidden="true" /> Bắt đầu miễn phí</li>
            </ul>
          </div>

          <div className={styles.productPreview} aria-label="Minh họa giao diện QueryMate">
            <div className={styles.previewTopbar}>
              <span /><span /><span />
              <small>QueryMate workspace</small>
            </div>
            <div className={styles.previewBody}>
              <div className={styles.previewSidebar}>
                <div className={styles.miniBrand}>AI</div>
                <span className={styles.activeNav}><MessageSquareText size={17} /></span>
                <span><TableProperties size={17} /></span>
                <span><BarChart3 size={17} /></span>
              </div>
              <div className={styles.previewContent}>
                <div className={styles.connectionPill}>
                  <span /> Sales Database · MySQL
                </div>
                <div className={styles.userBubble}>
                  Doanh thu theo từng tháng trong năm nay là bao nhiêu?
                </div>
                <div className={styles.aiAnswer}>
                  <div className={styles.answerHeading}>
                    <Bot size={17} /> QueryMate
                  </div>
                  <code>SELECT MONTH(order_date), SUM(amount)...</code>
                  <div className={styles.chartBars} aria-hidden="true">
                    <i style={{ height: "42%" }} /><i style={{ height: "63%" }} />
                    <i style={{ height: "52%" }} /><i style={{ height: "84%" }} />
                    <i style={{ height: "72%" }} /><i style={{ height: "96%" }} />
                  </div>
                  <p>Doanh thu cao nhất vào tháng 6 và tăng 33% so với tháng 1.</p>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section className={styles.compatibility} aria-label="Nguồn dữ liệu được hỗ trợ">
          <p>Kết nối các nguồn dữ liệu quen thuộc</p>
          <div><span>MY</span> MySQL</div>
          <div><span>PG</span> PostgreSQL</div>
          <div><FileSpreadsheet size={22} aria-hidden="true" /> Excel</div>
          <div><Sparkles size={22} aria-hidden="true" /> Gemini AI</div>
        </section>

        <section className={styles.section} id="tinh-nang" aria-labelledby="features-title">
          <div className={styles.sectionHeading}>
            <p className={styles.eyebrow}>Từ câu hỏi đến câu trả lời</p>
            <h2 id="features-title">Mọi thứ bạn cần để làm việc với dữ liệu</h2>
            <p>Không cần chuyển qua nhiều công cụ hay tự ghép từng bước thủ công.</p>
          </div>
          <div className={styles.featureGrid}>
            <article>
              <div className={styles.featureIcon}><MessageSquareText /></div>
              <h3>Hỏi bằng ngôn ngữ tự nhiên</h3>
              <p>Đặt câu hỏi bằng tiếng Việt hoặc tiếng Anh, QueryMate chuyển yêu cầu thành SQL phù hợp với schema.</p>
            </article>
            <article>
              <div className={styles.featureIcon}><ShieldCheck /></div>
              <h3>SQL chỉ đọc an toàn</h3>
              <p>Truy vấn được kiểm tra trước khi chạy để ngăn thao tác ghi hoặc thay đổi dữ liệu ngoài ý muốn.</p>
            </article>
            <article>
              <div className={styles.featureIcon}><BarChart3 /></div>
              <h3>Biểu đồ và nhận định</h3>
              <p>Biến kết quả thành biểu đồ phù hợp và nhận phần tóm tắt giúp bạn nhìn ra thông tin quan trọng.</p>
            </article>
            <article>
              <div className={styles.featureIcon}><Search /></div>
              <h3>Hiểu schema tự động</h3>
              <p>Đọc bảng, cột và quan hệ khóa để AI sử dụng đúng cấu trúc database khi tạo truy vấn.</p>
            </article>
            <article>
              <div className={styles.featureIcon}><Zap /></div>
              <h3>Theo dõi tiến độ trực tiếp</h3>
              <p>Xem từng bước tạo, kiểm tra và thực thi truy vấn ngay trong cuộc trò chuyện.</p>
            </article>
            <article>
              <div className={styles.featureIcon}><FileSpreadsheet /></div>
              <h3>Làm việc với Excel</h3>
              <p>Tải file .xlsx lên và dùng từng sheet như một bảng dữ liệu để hỏi đáp bằng AI.</p>
            </article>
          </div>
        </section>

        <section className={`${styles.section} ${styles.stepsSection}`} id="cach-hoat-dong" aria-labelledby="steps-title">
          <div className={styles.sectionHeading}>
            <p className={styles.eyebrow}>Đơn giản trong ba bước</p>
            <h2 id="steps-title">Bắt đầu phân tích trong vài phút</h2>
          </div>
          <ol className={styles.steps}>
            <li>
              <span>01</span><Database aria-hidden="true" />
              <div><h3>Kết nối nguồn dữ liệu</h3><p>Thêm MySQL, PostgreSQL bằng tài khoản chỉ đọc hoặc tải lên file Excel.</p></div>
            </li>
            <li>
              <span>02</span><MessageSquareText aria-hidden="true" />
              <div><h3>Đặt câu hỏi</h3><p>Viết điều bạn muốn biết bằng ngôn ngữ tự nhiên, không cần nhớ cú pháp SQL.</p></div>
            </li>
            <li>
              <span>03</span><BarChart3 aria-hidden="true" />
              <div><h3>Kiểm tra và khám phá</h3><p>Xem SQL, chạy truy vấn và đọc kết quả dưới dạng bảng, biểu đồ hoặc tóm tắt.</p></div>
            </li>
          </ol>
        </section>

        <section className={`${styles.section} ${styles.securitySection}`} id="bao-mat" aria-labelledby="security-title">
          <div className={styles.securityVisual} aria-hidden="true">
            <div className={styles.shieldCircle}><LockKeyhole /></div>
            <span className={styles.securityLine} />
            <div className={styles.databaseCard}><Database /><strong>Dữ liệu của bạn</strong><small>Quyền truy cập chỉ đọc</small></div>
          </div>
          <div className={styles.securityCopy}>
            <p className={styles.eyebrow}>Bảo mật ngay từ thiết kế</p>
            <h2 id="security-title">Kết nối dữ liệu với quyền kiểm soát rõ ràng</h2>
            <p>QueryMate được xây dựng để hỗ trợ phân tích mà không trao quyền thay đổi dữ liệu cho AI.</p>
            <ul>
              <li><ShieldCheck aria-hidden="true" /><span><strong>Tài khoản database chỉ đọc</strong>Giới hạn thao tác ở các truy vấn phục vụ phân tích.</span></li>
              <li><LockKeyhole aria-hidden="true" /><span><strong>Mã hóa thông tin kết nối</strong>Mật khẩu database được bảo vệ bằng AES-GCM trước khi lưu.</span></li>
              <li><TableProperties aria-hidden="true" /><span><strong>Dữ liệu tách biệt theo tài khoản</strong>Mỗi người chỉ quản lý connection và lịch sử của riêng mình.</span></li>
            </ul>
          </div>
        </section>

        <section className={styles.finalCta} aria-labelledby="cta-title">
          <div>
            <p className={styles.eyebrow}>Sẵn sàng khám phá dữ liệu?</p>
            <h2 id="cta-title">Biến câu hỏi của bạn thành câu trả lời rõ ràng.</h2>
            <p>Tạo tài khoản miễn phí và bắt đầu làm việc với dữ liệu ngay hôm nay.</p>
          </div>
          <Link className={styles.lightCta} to="/register">
            Đăng ký miễn phí <ArrowRight size={18} aria-hidden="true" />
          </Link>
        </section>
      </main>

      <footer className={styles.footer}>
        <a className={styles.brand} href="#top"><span>AI</span>QueryMate</a>
        <p>Trợ lý AI giúp bạn hiểu dữ liệu bằng ngôn ngữ tự nhiên.</p>
        <div><Link to="/login">Đăng nhập</Link><Link to="/register">Đăng ký</Link></div>
      </footer>
    </div>
  );
}
