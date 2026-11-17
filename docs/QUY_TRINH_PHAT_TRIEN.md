# Quy trinh phat trien CarAsistant

## 1. Moc nen tang

- Baseline: build #731
- Commit goc: b8abfba6643903b0b9f8486935a5a8e2059c96dd
- Nhanh nen tang: feature/carassistant-core-voice-decoupling
- Khong commit truc tiep len nhanh nen tang.

## 2. Quy uoc dat ten nhanh

Tat ca ten nhanh moi dung tieng Viet khong dau, chu thuong, ngan cach bang dau gach ngang.

- Nhanh tong hop: tong-hop/android-box
- Nhanh tinh nang: tinh-nang/<ten-tinh-nang>
- Vi du: tinh-nang/tu-khoi-dong-core
- Nhanh sua loi tinh nang: sua-loi/<ten-tinh-nang>

Moi nhanh tinh nang chi phuc vu mot tinh nang. Khong gom cac tinh nang doc lap vao cung mot nhanh.

## 3. Quy trinh tinh nang

1. Tao nhanh tinh nang tu dau nhanh tong hop hien tai.
2. Phat trien va tu kiem tra dung pham vi tinh nang.
3. Chay compile va unit tests.
4. Mo Pull Request vao tong-hop/android-box.
5. Review thay doi va kiem tra xung dot.
6. Chi merge khi CI dat va pham vi thay doi dung mot tinh nang.

Neu tinh nang chua dat, khong merge.

## 4. Quy trinh APK tong hop

1. Build APK tu nhanh tong-hop/android-box.
2. Ghi lai commit SHA, run ID, artifact va danh sach PR da tich hop.
3. Cai APK tren POCO F1 that.
4. Kiem tra voi Android Auto tren Honda BR-V that.
5. Ghi ro PASS/FAIL tung tieu chi; CI pass khong thay the test tren xe.
6. Khi dat, tao tag co dinh cho commit da nghiem thu.

## 5. Xu ly loi

- Loi thuoc tinh nang nao thi sua tren nhanh tinh nang do.
- Mo PR sua loi vao nhanh tong hop.
- Build lai APK tu nhanh tong hop va kiem tra lai.
- Loi tuong tac nhieu tinh nang: truy vet nguyen nhan, sua tai nhanh so huu code gay loi; khong sua nong tren nhanh tong hop.
- Khi chua ro nguyen nhan, dung tich hop them tinh nang moi.
- Luu lai APK va ket qua moi vong de co the doi chieu va quay lui.

## 6. Bao ve nen tang va tich hop

- Khong thay doi commit baseline #731.
- Khong merge truc tiep vao nhanh nen tang.
- Khong dua thay doi ngoai pham vi vao PR tinh nang.
- Khong xoa nhanh tinh nang truoc khi dot tich hop duoc nghiem thu.
- Moi lan build tong hop phai co commit SHA va danh sach thay doi ro rang.

## 7. Thu tu tinh nang du kien

1. tinh-nang/tu-khoi-dong-core
2. tinh-nang/phuc-hoi-core
3. tinh-nang/tich-hop-android-auto
4. tinh-nang/media-session
5. tinh-nang/phim-vo-lang
6. tinh-nang/tro-ly-giong-noi
7. tinh-nang/media-tu-phuc-hoi
8. tinh-nang/canh-bao-wyn

Danh sach tren la ke hoach. Chi tao va trien khai tung nhanh tiep theo khi tinh nang truoc da duoc tich hop va co moc kiem thu ro rang.

## 8. Trang thai kiem soat hien tai

- Workflow CI chi chay khi push vao tong-hop/android-box, khi co PR nham vao nhanh nay, hoac khi chay thu cong.
- CI thuc hien testDebugUnitTest va assembleDebug; artifact APK kem SHA-256 va BUILD_INFO.
- Tai thoi diem chot quy trinh, repository chua co ruleset nao. Vi vay GitHub chua tu dong chan merge khi CI that bai; nguoi tao PR phai doi CI xanh va review truoc khi merge.
- Khong tu nhan nhanh tong hop da duoc bao ve bang branch protection. Can bat quy tac bao ve trong GitHub Settings khi tai khoan co quyen admin.
- Build CI xanh chi xac nhan compile/unit test va tao APK; khong thay the kiem thu Android Auto tren POCO F1 va Honda BR-V.
- Nhanh tinh-nang/tu-khoi-dong-core hien chi la nhanh cho, chua co code tu khoi dong. Khi bat dau tinh nang, cap nhat nhanh nay tu HEAD moi nhat cua nhanh tong hop.
