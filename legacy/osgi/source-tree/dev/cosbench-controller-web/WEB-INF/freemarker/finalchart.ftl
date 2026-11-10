
<script type="text/javascript">
    function custage(){
        var d=document.getElementById("cstage").value;
        var element = document.getElementById("fchart");
        element.style.display ="";

        if(d=="close"){
             element.style.display ="none";
             return;
        }

        <#list info.stageInfos as sInfo >
            var id="${sInfo.id}";
            if(id==d){
                var i=0;
                var xvalue=new Array();
                var yvalue=new Array();
                var axis=new Array();

                //bandwidth
                var ybandread=new Array();
                var ybandwrite=new Array();
                var ybanddelete=new Array();
                var bandwidth;
                // 2023.6.14, sine. add other ops.
                var ybandlist=new Array(); // list
                var ybandfilewrite=new Array(); // filewrite
                var ybandprepare=new Array(); // prepare
                var ybandcleanup=new Array(); // cleanup
                var ybandinit=new Array(); // init
                var ybanddispose=new Array(); // dispose
                var ybandrestore=new Array(); // restore
                var ybandmwrite=new Array(); // mwrite
                var ybandhead=new Array(); // head
                var ybandmprepare=new Array(); // mprepare
                var ybandmfilewrite=new Array(); // mfilewrite
                var ybandlocalwrite=new Array(); // localwrite
    
                //throughput
                var ytputread=new Array();
                var ytputwrite=new Array();
                var ytputdelete=new Array();
                var sampletype;
                var throughput;
                // 2023.6.14, sine. add other ops.
                var ytputlist=new Array(); // list
                var ytputfilewrite=new Array(); // filewrite
                var ytputprepare=new Array(); // prepare
                var ytputcleanup=new Array(); // cleanup
                var ytputinit=new Array(); // init
                var ytputdispose=new Array(); // dispose
                var ytputrestore=new Array(); // restore
                var ytputmwrite=new Array(); // mwrite
                var ytputhead=new Array(); // head
                var ytputmprepare=new Array(); // mprepare
                var ytputmfilewrite=new Array(); // mfilewrite
                var ytputlocalwrite=new Array(); // localwrite

                //restime
                var yrtimeread=new Array();
                var yrtimewrite=new Array();
                var yrtimedelete=new Array();
                var avgResTime;
                // 2023.6.14, sine. add other ops.
                var yrtimelist=new Array(); // list
                var yrtimefilewrite=new Array(); // filewrite
                var yrtimeprepare=new Array(); // prepare
                var yrtimecleanup=new Array(); // cleanup
                var yrtimeinit=new Array(); // init
                var yrtimedispose=new Array(); // dispose
                var yrtimerestore=new Array(); // restore
                var yrtimemwrite=new Array(); // mwrite
                var yrtimehead=new Array(); // head
                var yrtimemprepare=new Array(); // mprepare
                var yrtimemfilewrite=new Array(); // mfilewrite
                var yrtimelocalwrite=new Array(); // localwrite

                //ratio
                var yraread=new Array();
                var yrawrite=new Array();
                var yradelete=new Array();
                var ratio;
                // 2023.6.14, sine. add other ops.
                var yralist=new Array(); // list
                var yrafilewrite=new Array(); // filewrite
                var yraprepare=new Array(); // prepare
                var yracleanup=new Array(); // cleanup
                var yrainit=new Array(); // init
                var yradispose=new Array(); // dispose
                var yrarestore=new Array(); // restore
                var yramwrite=new Array(); // mwrite
                var yrahead=new Array(); // head
                var yramprepare=new Array(); // mprepare
                var yramfilewrite=new Array(); // mfilewrite
                var yralocalwrite=new Array(); // localwrite

                <#list sInfo.snapshots as snapshot>
                    <#list snapshot.report.allMetrics as mInfo>
                        // bandwidth
                        bandwidth="${mInfo.bandwidth}";
                        bandwidth=bandwidth.replace(/,/gi,'');

                        //resTime
                        avgResTime="${mInfo.avgResTime}";
                        avgResTime=avgResTime.replace(/,/gi,'');
                    
                        //Succ-Ratio
                        ratio="${mInfo.ratio}";
                        ratio=ratio.replace(/,/gi,'');

                        // throughput
                        sampletype="${mInfo.sampleType}";
                        throughput="${mInfo.throughput}";
                        throughput=throughput.replace(/,/gi,'');
                        // 2023.6.14, sine. add other ops.
                        if(sampletype=="read"){
                            ytputread.push(Math.round(eval(throughput)));
                            ybandread.push(Math.round(eval(bandwidth/1024)));
                            yrtimeread.push(Math.round(eval(avgResTime)));
                            yraread.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="write"){
                            ytputwrite.push(Math.round(eval(throughput)));
                            ybandwrite.push(Math.round(eval(bandwidth/1024)));
                            yrtimewrite.push(Math.round(eval(avgResTime)));
                            yrawrite.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="delete"){
                            ytputdelete.push(Math.round(eval(throughput)));
                            ybanddelete.push(Math.round(eval(bandwidth/1024)));
                            yrtimedelete.push(Math.round(eval(avgResTime)));
                            yradelete.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="list"){
                            ytputlist.push(Math.round(eval(throughput)));
                            ybandlist.push(Math.round(eval(bandwidth/1024)));
                            yrtimelist.push(Math.round(eval(avgResTime)));
                            yralist.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="filewrite"){
                            ytputfilewrite.push(Math.round(eval(throughput)));
                            ybandfilewrite.push(Math.round(eval(bandwidth/1024)));
                            yrtimefilewrite.push(Math.round(eval(avgResTime)));
                            yrafilewrite.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="prepare"){
                            ytputprepare.push(Math.round(eval(throughput)));
                            ybandprepare.push(Math.round(eval(bandwidth/1024)));
                            yrtimeprepare.push(Math.round(eval(avgResTime)));
                            yraprepare.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="cleanup"){
                            ytputcleanup.push(Math.round(eval(throughput)));
                            ybandcleanup.push(Math.round(eval(bandwidth/1024)));
                            yrtimecleanup.push(Math.round(eval(avgResTime)));
                            yracleanup.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="init"){
                            ytputinit.push(Math.round(eval(throughput)));
                            ybandinit.push(Math.round(eval(bandwidth/1024)));
                            yrtimeinit.push(Math.round(eval(avgResTime)));
                            yrainit.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="dispose"){
                            ytputdispose.push(Math.round(eval(throughput)));
                            ybanddispose.push(Math.round(eval(bandwidth/1024)));
                            yrtimedispose.push(Math.round(eval(avgResTime)));
                            yradispose.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="restore"){
                            ytputrestore.push(Math.round(eval(throughput)));
                            ybandrestore.push(Math.round(eval(bandwidth/1024)));
                            yrtimerestore.push(Math.round(eval(avgResTime)));
                            yrarestore.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="mwrite"){
                            ytputmwrite.push(Math.round(eval(throughput)));
                            ybandmwrite.push(Math.round(eval(bandwidth/1024)));
                            yrtimemwrite.push(Math.round(eval(avgResTime)));
                            yramwrite.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="head"){
                            ytputhead.push(Math.round(eval(throughput)));
                            ybandhead.push(Math.round(eval(bandwidth/1024)));
                            yrtimehead.push(Math.round(eval(avgResTime)));
                            yrahead.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="mprepare"){
                            ytputmprepare.push(Math.round(eval(throughput)));
                            ybandmprepare.push(Math.round(eval(bandwidth/1024)));
                            yrtimemprepare.push(Math.round(eval(avgResTime)));
                            yramprepare.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="mfilewrite"){
                            ytputmfilewrite.push(Math.round(eval(throughput)));
                            ybandmfilewrite.push(Math.round(eval(bandwidth/1024)));
                            yrtimemfilewrite.push(Math.round(eval(avgResTime)));
                            yramfilewrite.push(Math.round(eval(ratio*100)));
                        }else if(sampletype=="localwrite"){
                            ytputlocalwrite.push(Math.round(eval(throughput)));
                            ybandlocalwrite.push(Math.round(eval(bandwidth/1024)));
                            yrtimelocalwrite.push(Math.round(eval(avgResTime)));
                            yralocalwrite.push(Math.round(eval(ratio*100)));
                        }
                    
                        //xvalue
                        xvalue.push(i);
                        i++;
                    </#list>
                </#list>
			
                //restime
                yvalue.length=0;
                if(yrtimeread.length>0){
                    yvalue.push(yrtimeread);
                    axis.push("read");
                }
                if(yrtimewrite.length>0){
                    yvalue.push(yrtimewrite);
                    axis.push("write");
                }
                if(yrtimedelete.length>0){
                    yvalue.push(yrtimedelete);
                    axis.push("delete");
                }
                // 2023.6.14, sine. add other ops.
                if(yrtimelist.length>0){
                    yvalue.push(yrtimelist);
                    axis.push("list");
                }
                if(yrtimefilewrite.length>0){
                    yvalue.push(yrtimefilewrite);
                    axis.push("filewrite");
                }
                if(yrtimeprepare.length>0){
                    yvalue.push(yrtimeprepare);
                    axis.push("prepare");
                }
                if(yrtimecleanup.length>0){
                    yvalue.push(yrtimecleanup);
                    axis.push("cleanup");
                }
                if(yrtimeinit.length>0){
                    yvalue.push(yrtimeinit);
                    axis.push("init");
                }
                if(yrtimedispose.length>0){
                    yvalue.push(yrtimedispose);
                    axis.push("dispose");
                }
                if(yrtimerestore.length>0){
                    yvalue.push(yrtimerestore);
                    axis.push("restore");
                }
                if(yrtimemwrite.length>0){
                    yvalue.push(yrtimemwrite);
                    axis.push("mwrite");
                }
                if(yrtimehead.length>0){
                    yvalue.push(yrtimehead);
                    axis.push("head");
                }
                if(yrtimemprepare.length>0){
                    yvalue.push(yrtimemprepare);
                    axis.push("mprepare");
                }
                if(yrtimemfilewrite.length>0){
                    yvalue.push(yrtimemfilewrite);
                    axis.push("mfilewrite");
                }
                if(yrtimelocalwrite.length>0){
                    yvalue.push(yrtimelocalwrite);
                    axis.push("localwrite");
                }
                
                if (yvalue.length>1) {
                	// if operation types total number > 1, we should set xvalue.length to yvalue[0].length
                	// because: 
                	// 		1. yvalue.length = operation type total number, also = axis.length
                	// 		2. xvalue.length = yvalue[0].length * operation type total number
			    	xvalue.length=yvalue[0].length;
			    }
                forchart(xvalue,yvalue,"resTime","sp",id,axis);
                
                //ratio
                yvalue.length=0;
                if(yraread.length>0){
                    yvalue.push(yraread);
                }
                if(yrawrite.length>0){
                    yvalue.push(yrawrite);
                }
                if(yradelete.length>0){
                    yvalue.push(yradelete);
                }
                // 2023.6.14, sine. add other ops.
                if(yralist.length>0){
                    yvalue.push(yralist);
                }
                if(yrafilewrite.length>0){
                    yvalue.push(yrafilewrite);
                }
                if(yraprepare.length>0){
                    yvalue.push(yraprepare);
                }
                if(yracleanup.length>0){
                    yvalue.push(yracleanup);
                }
                if(yrainit.length>0){
                    yvalue.push(yrainit);
                }
                if(yradispose.length>0){
                    yvalue.push(yradispose);
                }
                if(yrarestore.length>0){
                    yvalue.push(yrarestore);
                }
                if(yramwrite.length>0){
                    yvalue.push(yramwrite);
                }
                if(yrahead.length>0){
                    yvalue.push(yrahead);
                }
                if(yramprepare.length>0){
                    yvalue.push(yramprepare);
                }
                if(yramfilewrite.length>0){
                    yvalue.push(yramfilewrite);
                }
                if(yralocalwrite.length>0){
                    yvalue.push(yralocalwrite);
                }
                forchart(xvalue,yvalue,"ratio","%",id,axis);
                
                //bandwith
                yvalue.length=0;
                if(ybandread.length>0){
                    yvalue.push(ybandread);
                }
                if(ybandwrite.length>0){
                    yvalue.push(ybandwrite);
                }
                if(ybanddelete.length>0){
                    yvalue.push(ybanddelete);
                }
                // 2023.6.14, sine. add other ops.
                if(ybandlist.length>0){
                    yvalue.push(ybandlist);
                }
                if(ybandfilewrite.length>0){
                    yvalue.push(ybandfilewrite);
                }
                if(ybandprepare.length>0){
                    yvalue.push(ybandprepare);
                }
                if(ybandcleanup.length>0){
                    yvalue.push(ybandcleanup);
                }
                if(ybandinit.length>0){
                    yvalue.push(ybandinit);
                }
                if(ybanddispose.length>0){
                    yvalue.push(ybanddispose);
                }
                if(ybandrestore.length>0){
                    yvalue.push(ybandrestore);
                }
                if(ybandmwrite.length>0){
                    yvalue.push(ybandmwrite);
                }
                if(ybandhead.length>0){
                    yvalue.push(ybandhead);
                }
                if(ybandmprepare.length>0){
                    yvalue.push(ybandmprepare);
                }
                if(ybandmfilewrite.length>0){
                    yvalue.push(ybandmfilewrite);
                }
                if(ybandlocalwrite.length>0){
                    yvalue.push(ybandlocalwrite);
                }
                forchart(xvalue,yvalue,"bandwidth","KB/S",id,axis);
                
                //throughput
                yvalue.length=0;
                if(ytputread.length>0){
                    yvalue.push(ytputread);
                }
                if(ytputwrite.length>0){
                    yvalue.push(ytputwrite);
                }
                if(ytputdelete.length>0){
                    yvalue.push(ytputdelete);
                }
                // 2023.6.14, sine. add other ops.
                if(ytputlist.length>0){
                    yvalue.push(ytputlist);
                }
                if(ytputfilewrite.length>0){
                    yvalue.push(ytputfilewrite);
                }
                if(ytputprepare.length>0){
                    yvalue.push(ytputprepare);
                }
                if(ytputcleanup.length>0){
                    yvalue.push(ytputcleanup);
                }
                if(ytputinit.length>0){
                    yvalue.push(ytputinit);
                }
                if(ytputdispose.length>0){
                    yvalue.push(ytputdispose);
                }
                if(ytputrestore.length>0){
                    yvalue.push(ytputrestore);
                }
                if(ytputmwrite.length>0){
                    yvalue.push(ytputmwrite);
                }
                if(ytputhead.length>0){
                    yvalue.push(ytputhead);
                }
                if(ytputmprepare.length>0){
                    yvalue.push(ytputmprepare);
                }
                if(ytputmfilewrite.length>0){
                    yvalue.push(ytputmfilewrite);
                }
                if(ytputlocalwrite.length>0){
                    yvalue.push(ytputlocalwrite);
                }                
                forchart(xvalue,yvalue,"throughput","op/s",id,axis);
            }
        </#list>
	}
</script>