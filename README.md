# Break-In-Falsus 

如你所见，这是一个针对 [In Falsus](https://infalsus.lowiro.com) 的音游手柄项目，通过接受 陀螺仪 重力加速度计 加速度计 模拟鼠标，再配备触控面板模拟键盘6K，以解决抽象的创新立体（？节奏音游玩法。

[体验视频](https://www.bilibili.com/BV15goRBWE2X)

名字来源于 [brokenithm](https://github.com/tindy2013/Brokenithm-Android)，并对其架构进行了一定借鉴

欢迎加入 QQ 群一起讨论/求助 1095558304

感谢 **Contributor** !

- [班奥 Bnao](https://github.com/Bnao-zh) 负责了重力加速度，以及Linux端的实现，并打包了tauri版本

- [uuk](https://github.com/788009) 负责了 Python 服务器端的实现！

***

## 安装使用

项目分为服务端与客户端。

对于 root HID 模式（尚未完善，仅有AndroLua最小实现，且需配置内核描述）只需要单客户端即可

对于 网络层 模式（也更推荐），配置好服务端 客户端以及对应端口，关闭防火墙即可使用。

服务端由 python 脚本编写，支持[Windows](main-server.py), [Linux](linux-main-server.py)，只需要下载本项目根目录下的对应文件（点击上面超链接），补足运行库，运行即可。或者使用 release 打包的 tauri 封装产物

安卓端需要输入 电脑端的IP位置（通过 'ipconfig' 或者自带网络属性获得，通常为'192.168.x.x'）以及所使用的协议对应端口（默认UDP为5005 TCP为5006）

您有能力也可以开发适用于 Mac OS 的服务端，理论上只需要更换输出键鼠对应库。

客户端暂时只支持安卓，这是因为<del>主播没米</del>，您有能力可以在此架构上开发iOS版本(整个扔个codex, Claude都行，我会稳稳地接住您)。pr welcome!

## 实现方式

默认推荐实现方式是通过 UDP 连接，鼠标输入使用 重力加速度（GRAVITY），或使用 USB 转发 TCP 连接。

| 输出层 | 推荐度 | 介绍 | 配置难度 | 表现 |
|   ---   |   ---   | --- |    ---    |  ---  |
| UDP | 4/5 | 发包量小，延迟低 | 2/5 | 复杂网络环境下易丢包 |
| TCP | 5/5 | 通过3次握手提升稳定性，避免丢包 建议使用 'adb reverse tcp:5006 tcp:5006' 来以 USB 协议传输 | 3/5 | 表现极佳，除了配置部分玄学 |
| root HID | 2/5 | 设备要求较高，但是可以脱离服务端使用 | 5/5 | 延迟极低，但是配置需要 USB Gadget Tool ，难度较大 |
| 蓝牙 HID （开发中）| X/5 | 不确定，需进一步开发 | 5/5 | 。。。 |

| 输入层 | 推荐度 | 介绍 |
| --- | --- | --- |
| GRAVITY | 5/5 | 能自动归中，表现极佳 |
| GYRO | 4/5 | 支持相对坐标， 但是余数处理存在问题 |
| ACCEL | 2/5 | 无防抖，稳定性依托，不会真有人的设备不支持吧（ |

## 开发提示

项目存在大量 Gen-AI 内容，所以只要你拉得能看，review 通过即可。（

客户端以纯JVAV编写，分离了 Input Output 类，并在[MainActivity.java]实现。
