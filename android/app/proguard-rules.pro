# کتابخانه WireGuard از JNI استفاده می‌کند
-keep class com.wireguard.** { *; }
-keepclassmembers class com.wireguard.** { *; }

# کتابخانه WireGuard با annotationهای JSR-305 حاشیه‌نویسی شده ولی خود آن
# کتابخانه را همراه ندارد. این annotationها فقط زمان کامپایل معنا دارند و
# در زمان اجرا لازم نیستند، پس نبودشان اشکالی ندارد.
# بدون این خط، R8 در بیلد ریلیز با خطای «Missing class» متوقف می‌شود.
-dontwarn javax.annotation.**
