为了标识身份，建议先完成 Git 全局设置
git config --global user.name "yourname"
git config --global user.email "youremail@qq.com"

Windows提交时，检测出LF，不进行转换CRLF
git config --global core.autocrlf false

方式一：克隆仓库
git clone https://codeup.aliyun.com/610fc47c76b0c8e58d787018/cosbench-ehl/cosbench-ehualu.git
cd cosbench-ehualu
touch README.md
git add README.md
git commit -m "add README"
git push -u origin master

方式二：已有文件夹或仓库
cd existing_folder
git init
git remote add origin https://codeup.aliyun.com/610fc47c76b0c8e58d787018/cosbench-ehl/cosbench-ehualu.git
git add .
git commit -m "some messages"
git push -u origin master

方式三：导入代码库
git clone --bare https://git.example.com/your/project.git your_path
cd your_path
git remote set-url origin https://codeup.aliyun.com/610fc47c76b0c8e58d787018/cosbench-ehl/cosbench-ehualu.git
git push --mirror


Command line instructions

Git global setup
git config --global user.name "王贺新"
git config --global user.email "wanghx@ehualu.com"

Windows提交时，检测出LF，不进行转换CRLF
git config --global core.autocrlf false

Create a new repository
git clone http://gitlab/ehualu/cosbench.git
cd cosbench
touch README.md
git add README.md
git commit -m "add README"
git push -u origin master

Existing folder or Git repository
cd existing_folder
git init
git remote add origin http://gitlab/ehualu/cosbench.git
git add .
git commit
git push -u origin master