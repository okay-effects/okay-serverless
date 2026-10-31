package okay.serverless.cloud

/** keys of the tests' own, made by openssl for them alone: ES256 (P-256) and RS256, private as PKCS#8, public as SPKI */
object TestKeys:
  val ecPrivate: String =
    "-----BEGIN PRIVATE KEY-----\n" +
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgOBYBYOVD5MNjCMmP\n" +
    "eDlOIo9UhynTIqj3kmI/h6GmAumhRANCAAR8DMBvrxlYZNPh6DYqQ3HHyGlMR0vu\n" +
    "vJ3hVvQy+t0yiUqKLrX6WpWzA+2v8F8MDyM8Umt4zmLf/urP5sFxlWYK\n" +
    "-----END PRIVATE KEY-----\n"
  val ecPublic: String =
    "-----BEGIN PUBLIC KEY-----\n" +
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfAzAb68ZWGTT4eg2KkNxx8hpTEdL\n" +
    "7ryd4Vb0MvrdMolKii61+lqVswPtr/BfDA8jPFJreM5i3/7qz+bBcZVmCg==\n" +
    "-----END PUBLIC KEY-----\n"
  val rsaPrivate: String =
    "-----BEGIN PRIVATE KEY-----\n" +
    "MIIEugIBADANBgkqhkiG9w0BAQEFAASCBKQwggSgAgEAAoIBAQCswZRzHTGYdK4c\n" +
    "W48dOli9vOHM0RCXZFAsUYA1YfZTLi/rrm/k/+T+tLsZkkFpjkq/ysRQiFKszCSg\n" +
    "54S5u61eK7L6PW0G6Ww3S1DEozmPlrz4/jve9kcCOhiME3fgsH/j6r7gq6ckEH7N\n" +
    "huNrW704mtbn15wwWhTHQJD0S2DG87iQTSRWmXxQZYp4ExuSPny74jj/VcXiRfPZ\n" +
    "GQf2yPxK1+FRHayhLuzh1bt9/ow8b4YH0t4yXpoKQs38k4jFyqrONwfVeZnOUQR2\n" +
    "bJjlRL4M0ewdcGFwHrChsqkItwzMu0CblyARxhEaOw9j/azrwtw2v+ShfRFRmgdi\n" +
    "mwid8MdjAgMBAAECgf856e6Gf9P6kAEB/zj3II2M1YnjuoJNHEZExdwW/KAifeSL\n" +
    "520NZKRxhVLIwjZRhTJ3YWgNOehgKk/mr7h5TBy4QoP1DD9Q5RauEl/1kZLnJvMo\n" +
    "N+HKvuwIUplzQ7kAxulte8gYdPpRFATt4K6sjzPCy/rl8qoFaI7P4cWzeIrBg3xR\n" +
    "ySkSOSCD4GvzPJFjBcEnkCVmgrIfjph99HPDGDdIGH1qNjFYFDfG1p7t12P5w5Jg\n" +
    "Qsno3iEb08QPiRclCXWoAmuWi/3VgJ5ai3BLRZlJohJnoLM7hK93VmEuVBtMvVNI\n" +
    "iwo3oG6lhwUp++GUX4gnptSJVOri2BRqxXS4hfECgYEA0xlu/aydfEwrSjGdWwD3\n" +
    "BrAVp1YVTzvcNU95hJ+sh/NePgcIUl3vQ7U6Dx5NiOwjkrXcQySwNIj/7/sS9Hat\n" +
    "nxYW9eZPgq0Xv7La9I5+a4+5odUR6mdlZXWEGQCr04j7M9yOGrgSm47zk4D3Hb45\n" +
    "9wK4XMsKVXfcgQy8F/wL/XsCgYEA0YBSm6zJg/S2TizqmDOulKENwCijHNM1V+e3\n" +
    "8KL39BNmrNBDu217IReyIrTlw4/Q5sz9ayWGuCruTR4R3KB2wwNxfh+udSz8gmIY\n" +
    "u2QAP3dpqaKbF63kYJCGPhfUQM3mldkMl+Z8SzSzlJGZxK/mmW3Etsm/9PcuRnMc\n" +
    "x+661TkCgYBypciC2yG45QFJzUJhr1d5+V35QKZYHvGMvnoHzlR8jW8sjtM1MLEG\n" +
    "e8FpfpKrf1wejMhwateL4K/nnNuBi4bl9LBJomiiVvGj+jYyRcVhE3DE9JxDSx5l\n" +
    "iPpSPewT+bw8QVeoKTpp46xj+tG7BJokjgSk2Nzm6C8Io2rwN8InSwKBgEG1OLOg\n" +
    "5vdfKkqspcuk8g2l0dVCKZ6h0+pFQBjWVGSWLS7oh0Xi2aqQbwuZpzLGCaRKCPWx\n" +
    "KGbc+C7xznJfVcBmdRM0xpfzyyLtt4oWnElLn7VXzcMIhEeRLLmO6cCFk9vN3rhJ\n" +
    "zxQbbHOdkxfwf6gBmKAn+O/REw9/jThb9PdpAoGAHQm7nhX0zcp82a81/dmw4o3V\n" +
    "3PmCC4lQVEi4jyp0sze4KApxFZ611cnK9dJNLugKqmyKI3wiYdSme0iWKfhpwswm\n" +
    "5SQAZFhNcrSZQEjnhrp+NuOoJh8r39pRTcWVf5WIjpd0rLCQ3Op4FVK1r3BYVeR/\n" +
    "tFZesP+bUg9iC+9qjFU=\n" +
    "-----END PRIVATE KEY-----\n"
  val rsaPublic: String =
    "-----BEGIN PUBLIC KEY-----\n" +
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArMGUcx0xmHSuHFuPHTpY\n" +
    "vbzhzNEQl2RQLFGANWH2Uy4v665v5P/k/rS7GZJBaY5Kv8rEUIhSrMwkoOeEubut\n" +
    "Xiuy+j1tBulsN0tQxKM5j5a8+P473vZHAjoYjBN34LB/4+q+4KunJBB+zYbja1u9\n" +
    "OJrW59ecMFoUx0CQ9EtgxvO4kE0kVpl8UGWKeBMbkj58u+I4/1XF4kXz2RkH9sj8\n" +
    "StfhUR2soS7s4dW7ff6MPG+GB9LeMl6aCkLN/JOIxcqqzjcH1XmZzlEEdmyY5US+\n" +
    "DNHsHXBhcB6wobKpCLcMzLtAm5cgEcYRGjsPY/2s68LcNr/koX0RUZoHYpsInfDH\n" +
    "YwIDAQAB\n" +
    "-----END PUBLIC KEY-----\n"
